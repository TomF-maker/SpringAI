package com.example.springai.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.springai.dto.LoginRequest;
import com.example.springai.dto.LoginResponse;
import com.example.springai.dto.RegisterRequest;
import com.example.springai.dto.SendSmsCodeRequest;
import com.example.springai.dto.SendCodeRequest;
import com.example.springai.dto.VerifyLoginRequest;
import com.example.springai.entity.SysRole;
import com.example.springai.entity.SysUser;
import com.example.springai.entity.SysUserRole;
import com.example.springai.mapper.SysRoleMapper;
import com.example.springai.mapper.SysUserMapper;
import com.example.springai.mapper.SysUserRoleMapper;
import com.example.springai.service.EmailServiceI;
import com.example.springai.service.LoginSecurityServiceI;
import com.example.springai.service.SmsScene;
import com.example.springai.service.SmsServiceI;
import com.example.springai.service.VerificationCodeServiceI;
import com.example.springai.service.impl.SmsRateLimiter;
import com.example.springai.utils.IpUtils;
import com.example.springai.utils.JwtUtils;
import com.example.springai.utils.PhoneUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private SysUserMapper userMapper;
    // 注入 EmailService
    @Autowired
    private EmailServiceI emailService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SysRoleMapper roleMapper;

    @Autowired
    private SysUserRoleMapper userRoleMapper;
    @Autowired
    private VerificationCodeServiceI verificationCodeService;

    @Autowired
    private SmsServiceI smsService;

    @Autowired
    private LoginSecurityServiceI loginSecurityService;

    @Autowired
    private SmsRateLimiter smsRateLimiter;

    @Autowired
    private IpUtils ipUtils;

    /**
     * 短信功能总开关（{@code app.sms.enabled}）。
     *
     * <p>关闭时不发任何短信：注册回退到邮箱验证码流程、登录不做手机二次校验、
     * 两个短信接口直接拒绝。用于短信通道还没法真发的阶段。
     */
    @Value("${app.sms.enabled:false}")
    private boolean smsEnabled;

    /**
     * 密码登录。
     *
     * <p>除了校验密码，还要判断这次的来源网段是否可信：
     * 不可信则不下发 token，改发一个"手机验证码挑战"。
     */
    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        // 1. 认证
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 2. 从认证信息获取用户
        String username = authentication.getName();
        SysUser user = findByUsernameOrEmail(username);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        // 3. 异地登录判定
        String ipPrefix = ipUtils.toPrefix(ipUtils.getClientIp(httpRequest));
        LoginSecurityServiceI.Decision decision = loginSecurityService.decide(user, ipPrefix);

        if (!decision.isAllowed()) {
            String scene = decision.getScene();
            String challengeId = loginSecurityService.createChallenge(user.getId(), scene, ipPrefix);
            log.info("登录需要二次验证: userId={}, scene={}, 网段={}→{}",
                    user.getId(), scene, user.getLastLoginIp(), ipPrefix);

            return LoginResponse.builder()
                    .requirePhoneVerify(true)
                    .scene(scene)
                    .maskedPhone(PhoneUtils.mask(user.getPhone()))
                    .challengeId(challengeId)
                    .username(user.getUsername())
                    .realName(user.getRealName())
                    .userId(user.getId())
                    .build();
        }

        // 4. 放行：签发 token 并记录本次网段
        return issueToken(user, ipPrefix, null);
    }

    /**
     * 消费登录挑战，校验手机验证码后签发 token。
     *
     * <p>请求体只接受 challengeId / code / phone，用户身份一律从挑战记录里取。
     */
    @PostMapping("/verify-login")
    public LoginResponse verifyLogin(@RequestBody VerifyLoginRequest request, HttpServletRequest httpRequest) {
        requireSmsEnabled();
        String ipPrefix = ipUtils.toPrefix(ipUtils.getClientIp(httpRequest));

        // 场景必须在 redeem 之前读 —— redeem 成功后会销毁挑战记录
        LoginSecurityServiceI.ChallengeInfo info = loginSecurityService.getChallenge(request.getChallengeId());
        boolean isBind = info != null && LoginSecurityServiceI.SCENE_BIND.equals(info.getScene());

        LoginSecurityServiceI.RedeemResult result = loginSecurityService.redeem(
                request.getChallengeId(), request.getCode(), request.getPhone(), ipPrefix);

        if (!result.isSuccess()) {
            throw new RuntimeException(result.getMessage());
        }

        SysUser user = result.getUser();
        // BIND 场景要把新手机号写入；redeem 已校验过格式与唯一性
        String bindPhone = isBind ? PhoneUtils.normalize(request.getPhone()) : null;

        log.info("登录挑战通过: userId={}, scene={}, 网段={}",
                user.getId(), isBind ? "BIND" : "VERIFY", ipPrefix);
        return issueToken(user, ipPrefix, bindPhone);
    }

    /**
     * 发送短信验证码。
     *
     * <p>三个场景：注册（直接给手机号）、登录校验（由 challengeId 解析号码）、
     * 补绑手机号（给手机号 + BIND 场景）。
     */
    @PostMapping("/send-sms-code")
    public Map<String, Object> sendSmsCode(@RequestBody SendSmsCodeRequest request,
                                           HttpServletRequest httpRequest) {
        requireSmsEnabled();
        String phone = PhoneUtils.normalize(request.getPhone());
        String scene = request.getScene() == null ? SmsScene.REGISTER : request.getScene();

        // 登录挑战场景：号码从服务端解析，前端不必知道完整手机号
        if (request.getChallengeId() != null && !request.getChallengeId().isBlank()) {
            LoginSecurityServiceI.ChallengeInfo info =
                    loginSecurityService.getChallenge(request.getChallengeId());
            if (info == null) {
                throw new RuntimeException("验证已过期，请重新登录");
            }
            SysUser user = userMapper.selectById(info.getUserId());
            if (user == null) {
                throw new RuntimeException("用户不存在");
            }
            if (LoginSecurityServiceI.SCENE_BIND.equals(info.getScene())) {
                scene = SmsScene.BIND;
            } else {
                scene = SmsScene.LOGIN;
                phone = PhoneUtils.normalize(user.getPhone());
            }
        }

        if (phone == null) {
            throw new RuntimeException("手机号格式不正确");
        }

        // 注册场景先查重，免得用户填完验证码才发现号码被占用
        if (SmsScene.REGISTER.equals(scene) && findByPhone(phone) != null) {
            throw new RuntimeException("该手机号已被注册");
        }

        smsRateLimiter.checkAndRecord(phone, ipUtils.getClientIp(httpRequest));
        try {
            smsService.sendCode(phone, scene);
        } catch (RuntimeException e) {
            smsRateLimiter.releaseCooldown(phone);
            throw e;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "验证码已发送");
        result.put("maskedPhone", PhoneUtils.mask(phone));
        return result;
    }

    /**
     * 发送邮箱验证码（保留原有能力，密码重置等流程仍在用）。
     */
    @PostMapping("/send-code")
    public Map<String, Object> sendCode(@RequestBody SendCodeRequest request) {
        Map<String, Object> result = new HashMap<>();
        try {
            emailService.sendVerificationCode(request.getEmail());
            result.put("code", 200);
            result.put("message", "验证码已发送");
        } catch (Exception e) {
            result.put("code", 500);
            result.put("message", "发送失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 注册。
     *
     * <p>有两条路径，由 {@code app.sms.enabled} 决定：
     * <ul>
     *   <li><b>开</b>：手机号必填 + 短信验证码校验，邮箱可选。</li>
     *   <li><b>关</b>：回退到原来的邮箱验证码流程，手机号可选——
     *       短信不可用时若还强制手机验证码，注册会直接不可用。</li>
     * </ul>
     */
    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody RegisterRequest request) {
        Map<String, Object> response = new HashMap<>();

        // 1. 手机号：短信开启时必填并使用短信验证码；关闭时可选，改用邮箱验证码
        String phone = PhoneUtils.normalize(request.getPhone());
        String email = (request.getEmail() == null || request.getEmail().isBlank())
                ? null : request.getEmail();

        if (smsEnabled) {
            if (phone == null) {
                response.put("code", 400);
                response.put("message", "请填写正确的手机号");
                return response;
            }
            if (!smsService.verifyCode(phone, SmsScene.REGISTER, request.getCode())) {
                response.put("code", 400);
                response.put("message", "验证码错误或已过期");
                return response;
            }
        } else {
            if (email == null) {
                response.put("code", 400);
                response.put("message", "请填写邮箱");
                return response;
            }
            if (!verificationCodeService.verify(email, request.getCode())) {
                response.put("code", 400);
                response.put("message", "验证码错误或已过期");
                return response;
            }
        }

        // 2. 用户名查重
        SysUser existingByUsername = userMapper.selectOne(
                new QueryWrapper<SysUser>().eq("username", request.getUsername())
        );
        if (existingByUsername != null) {
            response.put("code", 400);
            response.put("message", "用户名已被占用");
            return response;
        }

        // 3. 手机号查重（仅在填了手机号时）
        if (phone != null && findByPhone(phone) != null) {
            response.put("code", 400);
            response.put("message", "该手机号已被注册");
            return response;
        }

        // 4. 邮箱查重（仅在填了邮箱时）
        if (email != null) {
            SysUser existingByEmail = userMapper.selectOne(
                    new QueryWrapper<SysUser>().eq("email", email)
            );
            if (existingByEmail != null) {
                response.put("code", 400);
                response.put("message", "邮箱已被注册");
                return response;
            }
        }

        // 5. 创建新用户
        SysUser user = new SysUser();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setEmail(email);
        user.setPhone(phone);
        user.setRealName(request.getRealName() != null ? request.getRealName() : request.getUsername());
        user.setUserType(1);           // 默认内部员工
        user.setStatus(1);             // 默认启用
        user.setIsAdmin(0);            // 默认非管理员
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());

        userMapper.insert(user);

        // 6. 分配默认角色（USER角色，role_id = 3）
        SysUserRole userRole = new SysUserRole();
        userRole.setUserId(user.getId());
        userRole.setRoleId(3L);        // USER 角色的 ID（根据你的数据库实际值调整）
        userRole.setCreatedAt(LocalDateTime.now());
        userRoleMapper.insert(userRole);

        response.put("code", 200);
        response.put("message", "注册成功");
        response.put("userId", user.getId());
        return response;
    }

    /** 短信相关接口在总开关关闭时直接拒绝，避免前端拿到一个永远收不到的验证码。 */
    private void requireSmsEnabled() {
        if (!smsEnabled) {
            throw new RuntimeException("手机验证码功能未开启（app.sms.enabled=false）");
        }
    }

    // ==================== 内部方法 ====================

    private LoginResponse issueToken(SysUser user, String ipPrefix, String bindPhone) {
        String token = jwtUtils.generateToken(user.getUsername());

        // 记录网段、刷新白名单；BIND 场景同时补绑手机号
        loginSecurityService.markLoginSuccess(user.getId(), ipPrefix, bindPhone);

        user.setLastLoginTime(LocalDateTime.now());
        userMapper.updateById(user);

        return LoginResponse.builder()
                .token(token)
                .username(user.getUsername())
                .realName(user.getRealName())
                .userId(user.getId())
                .requirePhoneVerify(false)
                .build();
    }

    private SysUser findByUsernameOrEmail(String usernameOrEmail) {
        return userMapper.selectOne(
                new QueryWrapper<SysUser>()
                        .eq("username", usernameOrEmail)
                        .or()
                        .eq("email", usernameOrEmail)
        );
    }

    private SysUser findByPhone(String normalizedPhone) {
        return userMapper.selectOne(
                new QueryWrapper<SysUser>().eq("phone", normalizedPhone)
        );
    }
}
