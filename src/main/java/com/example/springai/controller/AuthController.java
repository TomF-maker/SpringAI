package com.example.springai.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.springai.common.EmailFormat;
import com.example.springai.common.ErrorCode;
import com.example.springai.common.Response;
import com.example.springai.dto.CaptchaResponse;
import com.example.springai.dto.LoginRequest;
import com.example.springai.dto.LoginResponse;
import com.example.springai.dto.RegisterRequest;
import com.example.springai.dto.ResetPasswordRequest;
import com.example.springai.dto.SendSmsCodeRequest;
import com.example.springai.dto.SendCodeRequest;
import com.example.springai.dto.VerifyLoginRequest;
import com.example.springai.entity.KbLoginLog;
import com.example.springai.entity.SysCompany;
import com.example.springai.entity.SysUser;
import com.example.springai.mapper.SysUserMapper;
import com.example.springai.exception.BizException;
import com.example.springai.service.CaptchaServiceI;
import com.example.springai.service.CompanyServiceI;
import com.example.springai.service.EmailServiceI;
import com.example.springai.service.impl.EmailCodeRateLimiter;
import com.example.springai.service.impl.LoginAttemptLimiter;
import com.example.springai.service.LoginLogServiceI;
import com.example.springai.service.LoginSecurityServiceI;
import com.example.springai.service.SmsScene;
import com.example.springai.service.SmsServiceI;
import com.example.springai.service.VerificationCodeServiceI;
import com.example.springai.service.impl.SmsRateLimiter;
import com.example.springai.utils.IpUtils;
import com.example.springai.utils.JwtUtils;
import com.example.springai.utils.PasswordGenerator;
import com.example.springai.utils.PhoneUtils;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
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
    private VerificationCodeServiceI verificationCodeService;

    @Autowired
    private SmsServiceI smsService;

    @Autowired
    private LoginSecurityServiceI loginSecurityService;

    @Autowired
    private SmsRateLimiter smsRateLimiter;

    @Autowired
    private IpUtils ipUtils;

    @Autowired
    private LoginLogServiceI loginLogService;

    @Autowired
    private CompanyServiceI companyService;

    /** 登录密码重试限制（账号锁定 + IP 节流）。此前完全没有，等于爆破成本为零。 */
    @Autowired
    private LoginAttemptLimiter loginAttemptLimiter;

    /** 邮箱验证码发送限流。此前这个接口完全裸露，可以拿任意邮箱反复触发发信。 */
    @Autowired
    private EmailCodeRateLimiter emailCodeRateLimiter;

    /** 图形验证码：发码接口的第一道门，挡的是"拿 Postman 直接刷"这类脚本化滥用。 */
    @Autowired
    private CaptchaServiceI captchaService;

    /**
     * 图形验证码总开关（{@code app.captcha.enabled}），默认**开**。
     *
     * <p>留着它是因为画图依赖 AWT —— 万一服务器上字体/headless 出问题，
     * 有个不停机就能降级的口子（代价是发码接口裸奔，所以关闭时会打启动横幅）。
     * 开关的判定只放在 {@link #requireCaptcha} 一处，别散到调用点上去。
     */
    @Value("${app.captcha.enabled:true}")
    private boolean captchaEnabled;

    /**
     * 短信功能总开关（{@code app.sms.enabled}）。
     *
     * <p>关闭时不发任何短信：注册回退到邮箱验证码流程、登录不做手机二次校验、
     * 两个短信接口直接拒绝。用于短信通道还没法真发的阶段。
     */
    /**
     * 密码最短长度。和 register.html / forgot-password.html 里的 {@code minlength} 是同一个值，
     * 改一处必须改另一处 —— 前端那个只是体验，**这里才是约束**。
     */
    private static final int MIN_PASSWORD_LENGTH = PasswordGenerator.MIN_LENGTH;

    @Value("${app.sms.enabled:false}")
    private boolean smsEnabled;

    /**
     * 密码登录。
     *
     * <p>除了校验密码，还要判断这次的来源网段是否可信：
     * 不可信则不下发 token，改发一个"手机验证码挑战"。
     */
    @PostMapping("/login")
    public Response<LoginResponse> login(@RequestBody LoginRequest request,
                                         HttpServletRequest httpRequest) {
        // 完整 IP 取一次复用：登录尝试限流、异地风控、登录审计都要用。
        // 从"认证之后"提到了"认证之前"—— 见下面第 1 步。
        String clientIp = ipUtils.getClientIp(httpRequest);

        // 1. 登录尝试限流（账号锁定 + IP 节流）。
        // **必须在 authenticate 之前**：放到后面就是"先把密码比完，再决定该不该让他试"，
        // 那等于没限流 —— 爆破者照样能试完所有密码，只是失败时多收一个提示。
        loginAttemptLimiter.checkAllowed(request.getUsername(), clientIp);

        // 2. 认证
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
            );
        } catch (DisabledException e) {
            // 理论上到不了这里：Spring Security 7 的 DaoAuthenticationProvider 会先把
            // 它包成 InternalAuthenticationServiceException（见下面那个 catch）。
            // 留着是廉价的保险 —— 万一某个版本不再包裹，行为也不会退化成 500。
            log.info("登录被公司状态拦下: username={}, 原因={}", request.getUsername(), e.getMessage());
            return Response.fail(ErrorCode.FORBIDDEN, e.getMessage());
        } catch (InternalAuthenticationServiceException e) {
            // **这里是实际命中的分支。** Spring Security 7 的
            // DaoAuthenticationProvider.retrieveUser 会把 loadUserByUsername 里抛出的
            // 任何 AuthenticationException 包成 InternalAuthenticationServiceException ——
            // 我们那个 DisabledException（公司停用 / 合约到期）到这一层已经换了类型，
            // 所以必须看 cause，不能只看类型。
            //
            // 认不出来的（cause 不是 DisabledException）就是真·内部故障（比如数据库连不上）：
            // 按原来的方式计数并抛出去，让它落成 500 —— 那是运维该看见的信号，
            // 不能伪装成"贵司已到期"。
            if (e.getCause() instanceof DisabledException) {
                log.info("登录被公司状态拦下: username={}, 原因={}", request.getUsername(), e.getMessage());
                // 不计入登录重试：这是商业状态，不是有人在猜密码。
                // 计进去的话，客户欠费期间反复尝试会把账号也锁上，续费后还要再解锁一次。
                return Response.fail(ErrorCode.FORBIDDEN, e.getMessage());
            }
            log.warn("认证过程内部异常: username={}, {}", request.getUsername(), e.getMessage());
            loginAttemptLimiter.recordFailure(request.getUsername());
            throw e;
        } catch (AuthenticationException e) {
            // 失败要计数。注意 BadCredentialsException 在 Spring 里也用来表示"用户不存在"
            // （避免用户名枚举），所以这里按"一次失败的登录尝试"计是合适的。
            loginAttemptLimiter.recordFailure(request.getUsername());
            throw e;   // 原样抛出：全局处理器靠它的类型返回 401，**别改成 200**
        }
        // 成功就清零，别让几次手滑累积到后面把人锁了
        loginAttemptLimiter.recordSuccess(request.getUsername());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 3. 从认证信息获取用户
        String username = authentication.getName();
        SysUser user = findByUsernameOrEmail(username);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        // 4. 异地登录判定
        // 网段用 IP 的 /24 或 /64 前缀（前缀才是风控该比的，见 IpUtils.toPrefix 的说明）。
        String ipPrefix = ipUtils.toPrefix(clientIp);
        LoginSecurityServiceI.Decision decision = loginSecurityService.decide(user, ipPrefix);

        if (!decision.isAllowed()) {
            String scene = decision.getScene();
            String challengeId = loginSecurityService.createChallenge(user.getId(), scene, ipPrefix);
            log.info("登录需要二次验证: userId={}, scene={}, 网段={}→{}",
                    user.getId(), scene, user.getLastLoginIp(), ipPrefix);

            return Response.success(LoginResponse.builder()
                    .requirePhoneVerify(true)
                    .scene(scene)
                    .maskedPhone(PhoneUtils.mask(user.getPhone()))
                    .challengeId(challengeId)
                    .username(user.getUsername())
                    .realName(user.getRealName())
                    .userId(user.getId())
                    .build());
        }

        // 5. 放行：签发 token 并记录本次网段
        return Response.success(issueToken(user, ipPrefix, null, clientIp, KbLoginLog.TYPE_PASSWORD));
    }

    /**
     * 消费登录挑战，校验手机验证码后签发 token。
     *
     * <p>请求体只接受 challengeId / code / phone，用户身份一律从挑战记录里取。
     */
    @PostMapping("/verify-login")
    public Response<LoginResponse> verifyLogin(@RequestBody VerifyLoginRequest request,
                                               HttpServletRequest httpRequest) {
        requireSmsEnabled();
        String clientIp = ipUtils.getClientIp(httpRequest);
        String ipPrefix = ipUtils.toPrefix(clientIp);

        // 场景必须在 redeem 之前读 —— redeem 成功后会销毁挑战记录
        LoginSecurityServiceI.ChallengeInfo info = loginSecurityService.getChallenge(request.getChallengeId());
        boolean isBind = info != null && LoginSecurityServiceI.SCENE_BIND.equals(info.getScene());

        LoginSecurityServiceI.RedeemResult result = loginSecurityService.redeem(
                request.getChallengeId(), request.getCode(), request.getPhone(), ipPrefix);

        if (!result.isSuccess()) {
            throw new RuntimeException(result.getMessage());
        }

        SysUser user = result.getUser();
        // 二次验证这条路**绕过了 loadUserByUsername**（用户是从挑战记录里取出来的），
        // 所以公司状态的判定要在这里再做一次 —— 否则欠费的客户通过短信验证
        // 仍然能拿到 token。
        assertCompanyUsable(user);
        // BIND 场景要把新手机号写入；redeem 已校验过格式与唯一性
        String bindPhone = isBind ? PhoneUtils.normalize(request.getPhone()) : null;

        log.info("登录挑战通过: userId={}, scene={}, 网段={}",
                user.getId(), isBind ? "BIND" : "VERIFY", ipPrefix);
        return Response.success(issueToken(user, ipPrefix, bindPhone, clientIp, KbLoginLog.TYPE_SMS_VERIFY));
    }

    /**
     * 发送短信验证码。
     *
     * <p>三个场景：注册（直接给手机号）、登录校验（由 challengeId 解析号码）、
     * 补绑手机号（给手机号 + BIND 场景）。
     */
    @PostMapping("/send-sms-code")
    public Response<Map<String, Object>> sendSmsCode(@RequestBody SendSmsCodeRequest request,
                                                     HttpServletRequest httpRequest) {
        requireSmsEnabled();

        // 图形验证码是**唯一的第一道门**，排在任何有副作用的检查之前（详见 sendCode 的注释）。
        // 这里没有先判手机号格式是因为做不到：登录挑战场景下号码由 challengeId 解析，
        // 请求体里的 phone 本来就允许为空 —— 想在校验图码之前统一判格式，
        // 就得把 challengeId 的解析逻辑抄一份上来。
        requireCaptcha(request.getCaptchaId(), request.getCaptchaCode());

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

        Map<String, Object> data = new HashMap<>();
        data.put("maskedPhone", PhoneUtils.mask(phone));
        return Response.success(data);
    }

    /**
     * 发送邮箱验证码。注册和忘记密码两条流程都走它。
     *
     * <p>发送失败不再吞掉返回 200：SMTP 挂了属于服务端故障，直接抛出去，
     * 由全局处理器返回 500 + 信封，前端看 {@code success:false} 即可。
     *
     * <p><b>限流在这里加</b>：此前这个接口完全裸露，任何人都能拿任意邮箱反复触发发信。
     * 和短信那边一样，发送失败时要归还冷却名额，否则一次 SMTP 抖动会把用户锁 60 秒。
     */
    @PostMapping("/send-code")
    public Response<Void> sendCode(@RequestBody SendCodeRequest request,
                                   HttpServletRequest httpRequest) {
        // 纯输入校验（无副作用）先跑，别让一个明显不合法的请求白白烧掉一张图形验证码 ——
        // 图形验证码是**一次性**的，消耗了就得重新认一张图。
        String email = request.getEmail() == null ? null : request.getEmail().trim();
        if (email == null || email.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请填写邮箱");
        }
        // 格式不对就在这里挡掉。否则它会一路走到 QQ SMTP，jakarta.mail 解析收件人时抛
        // AddressException，用户看到的是 HTTP 500 —— 一个"你地址打错了"的事故
        // 被包装成了"服务器内部错误"，还白烧掉一次图形验证码。
        if (!EmailFormat.isValid(email)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "邮箱格式不正确，请检查后重新填写");
        }

        // 图形验证码：**唯一的第一道门**。它之后才允许出现有副作用的检查 ——
        // 顺序反过来（先限流再验图码）的话，一个不解图码的攻击者只要每 59 秒打一次
        // victim@x.com，就能靠 EmailCodeRateLimiter 的 SETNX 把那个邮箱**永久钉死在冷却里**：
        // 受害者的真实请求永远收到"发送过于频繁"，注册和找回密码直接不可用。
        //
        // 另外：这个调用**必须留在下面那个 try 之外**。BizException 也是 RuntimeException，
        // 落进那个 catch 就会顺手把刚设上的 60 秒冷却删掉（releaseCooldown），限流形同虚设。
        requireCaptcha(request.getCaptchaId(), request.getCaptchaCode());

        String clientIp = ipUtils.getClientIp(httpRequest);
        emailCodeRateLimiter.checkAndRecord(email, clientIp);

        try {
            emailService.sendVerificationCode(email);
        } catch (RuntimeException e) {
            emailCodeRateLimiter.releaseCooldown(email);
            throw e;
        }
        return Response.success();
    }

    /**
     * 签发一张图形验证码。匿名可调 —— 注册和找回密码的人本来就还没登录。
     *
     * <p>用 JSON + base64 而不是直接返回图片：{@code <img src>} 加载失败只能显示一个碎图标，
     * 签发限流（429）和 Redis 抖动（500）都没有地方展示；而且图片 URL 会被浏览器缓存，
     * "点了刷新还是同一张图"是个经典坑。
     *
     * <p>{@code Cache-Control: no-store} 是必须的：这个响应体里就是明文答案，
     * 被中间层或浏览器缓存住的话，下一个请求拿到的还是同一张图。
     */
    @GetMapping("/captcha")
    public Response<CaptchaResponse> captcha(HttpServletRequest httpRequest,
                                             HttpServletResponse httpResponse) {
        httpResponse.setHeader("Cache-Control", "no-store");
        String clientIp = ipUtils.getClientIp(httpRequest);
        return Response.success(captchaService.issue(clientIp));
    }

    /**
     * 图形验证码的唯一开关点。
     *
     * <p>开关**只放在这一处**：{@code CaptchaService.verify} 永远说真话，
     * 否则每个调用点、每个单测都要多问一句"这个 true 是真通过了还是被关掉了"。
     *
     * <p>{@code :true} 这个默认值不能改成 {@code :false}：后者意味着配置里一个拼错的
     * key 就能**静默**关掉全部防护，而没有任何地方会报错。
     */
    private void requireCaptcha(String captchaId, String captchaCode) {
        if (!captchaEnabled) {
            return;
        }
        if (!captchaService.verify(captchaId, captchaCode)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "图形验证码错误或已过期");
        }
    }

    /**
     * 图形验证码总开关关闭时的开机横幅。
     *
     * <p>照 {@code app.sms.enabled} 的做法：危险的开关必须在启动日志里喊一声，
     * 否则"为什么 Postman 还能直接刷验证码"要查到配置文件才会明白。
     */
    @PostConstruct
    void warnIfCaptchaDisabled() {
        if (!captchaEnabled) {
            log.warn("⚠️ 图形验证码已关闭（app.captcha.enabled=false）—— "
                    + "/api/auth/send-code 与 /api/auth/send-sms-code 不再要求认图，等于裸奔");
        }
    }

    /**
     * 忘记密码：用邮箱验证码重置密码。
     *
     * <p>流程是「{@code /send-code} 发码 → 这里验码 + 改密码」，全程**不需要登录**
     * —— 用户就是因为登不上去才走这条路。
     *
     * <p>几个刻意的取舍：
     * <ul>
     *   <li><b>密码长度校验放在验码之前。</b>{@code verify} 是**一次性**的，
     *       成功即删码；先验码再发现新密码太短的话，用户得重新收一次邮件。
     *       密码校验是纯输入检查、没有副作用，提到最前面。</li>
     *   <li><b>验码放在查用户之前。</b>反过来就等于提供了一个
     *       "输入邮箱就知道注册没注册"的探测接口。</li>
     *   <li><b>不改账号状态、也不签发 token。</b>被停用的账号允许改密码，
     *       但改完仍登不进去 —— 那道检查在 {@code UserDetailsServiceImpl}，
     *       这里既不重复也不绕过。改完要求用户自己回登录页重新登一次。</li>
     * </ul>
     *
     * <p><b>没有速率限制</b>：{@code /send-code} 本身就没有，所以这里也没加。
     * 也就是说可以反复触发给某个邮箱发信。要收紧就在 {@code /send-code} 上做，
     * 别只加在这里（那等于绕过）。
     */
    @PostMapping("/reset-password")
    public Response<Void> resetPassword(@RequestBody ResetPasswordRequest request) {
        String email = request.getEmail() == null ? null : request.getEmail().trim();
        if (email == null || email.isEmpty()) {
            throw new BizException("请填写邮箱");
        }

        // 先做纯输入校验，避免白白消耗掉一次性验证码
        String newPassword = request.getNewPassword();
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new BizException("新密码至少 " + MIN_PASSWORD_LENGTH + " 位");
        }

        if (!verificationCodeService.verify(email, request.getCode())) {
            throw new BizException("验证码错误或已过期");
        }

        SysUser user = userMapper.selectOne(
                new QueryWrapper<SysUser>().eq("email", email).last("LIMIT 1"));
        if (user == null) {
            throw new BizException("该邮箱未注册");
        }

        // 只写 id + password 两列。不要用整个实体 updateById ——
        // 那会把读出来的旧快照（lastLoginIp、points、memberType…）一起写回去，
        // 这个坑在 issueToken 里踩过一次，见 CLAUDE.md。
        SysUser update = new SysUser();
        update.setId(user.getId());
        update.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(update);

        log.info("🔑 用户通过邮箱验证码重置了密码: userId={}", user.getId());
        return Response.success();
    }

    /**
     * 注册接口 —— <b>已停用（A4 开户收口）</b>。
     *
     * <p>改成"谁来都不能自助注册"是商业上的决定：按公司年费收费却又开着自助注册，
     * 等于没付费也能进来用；而且那套"填信用代码自动建公司"的流程，本身就在暗示
     * "客户可以自己开户"。
     *
     * <p><b>现在开户只有一条路</b>：内部管理员在公司管理页新建公司
     * （{@code /api/companies}）→ 在该公司下批量导入员工（{@code /api/users/import}）。
     *
     * <p><b>接口必须停用，不能只改页面</b>：把 register.html 换成说明页只是引导，
     * 直接打这个接口（curl / 改 JS）照样能建号 —— 见 pitfalls.md「前端校验不是约束」。
     * 所以路由保留、方法直接拒绝，返回 403 加一句能照着做的话。
     *
     * <p>原来的逻辑是**整体删除**的，没有留成"永远不执行的分支"：
     * 死代码比没有代码更容易误导人（pitfalls.md 第 15 条）。要看旧实现走 git 历史；
     * 其中用到的 {@code CompanyService.resolveOrCreate} 仍然保留 ——
     * 个人中心"补填公司信息"还在调它。
     *
     * <p>存量账号不受影响：这里只管"新注册"，已有账号照常登录。
     */
    @PostMapping("/register")
    public Response<Map<String, Object>> register(@RequestBody RegisterRequest request) {
        log.info("🚫 自助注册已关闭，拒绝注册请求: username={}",
                request == null ? null : request.getUsername());
        return Response.fail(ErrorCode.FORBIDDEN, "本系统不开放自助注册，请联系我们开通账号");
    }

    /** 短信相关接口在总开关关闭时直接拒绝，避免前端拿到一个永远收不到的验证码。 */
    private void requireSmsEnabled() {
        if (!smsEnabled) {
            throw new RuntimeException("手机验证码功能未开启（app.sms.enabled=false）");
        }
    }

    // ==================== 内部方法 ====================

    private LoginResponse issueToken(SysUser user, String ipPrefix, String bindPhone,
                                     String clientIp, String loginType) {
        String token = jwtUtils.generateToken(user.getUsername());

        // 记录网段、刷新白名单；BIND 场景同时补绑手机号
        loginSecurityService.markLoginSuccess(user.getId(), ipPrefix, bindPhone);

        // 登录审计。放在这里是因为这是两条登录路径（密码直登 / 异地验证）的唯一收口点，
        // 一处就能覆盖全。LoginLogService 内部吞掉所有异常 —— 审计坏了不能把人挡在门外。
        loginLogService.record(user, loginType, clientIp, ipPrefix);

        // 只更新"最后登录时间"这一列。
        //
        // **不要写成 `user.setLastLoginTime(...); userMapper.updateById(user);`** ——
        // 那个 user 是登录开始时从库里读出来的**旧快照**，updateById 会写它所有非 null
        // 字段，于是刚在 markLoginSuccess 里写对的新网段会被它携带的旧值覆盖回去。
        //
        // 症状很好认：last_login_time 是新的、last_login_ip 却是旧的。而且因为第一次
        // 登录时旧值是 null（MyBatis-Plus 跳过 null），那次能写进去 —— 之后就被
        // **永久冻在第一个值上**，换网络也永不更新。而这个 bug 只在换网络时才暴露，
        // 那正是这个字段存在的意义。
        //
        // 这里传一个只设了 id 和目标列的**部分对象**：MyBatis-Plus 跳过 null 字段，
        // 所以 SQL 里只有 id 和 last_login_time，结构上不可能覆盖任何别的列 ——
        // 比"记得把脏字段置空"可靠，也不会顺带写回整个旧快照。
        SysUser loginTimeUpdate = new SysUser();
        loginTimeUpdate.setId(user.getId());
        loginTimeUpdate.setLastLoginTime(LocalDateTime.now());
        userMapper.updateById(loginTimeUpdate);

        return LoginResponse.builder()
                .token(token)
                .username(user.getUsername())
                .realName(user.getRealName())
                .userId(user.getId())
                // 前端侧边栏靠这两个值区分"内部管理员"与"客户管理员"，
                // 不要只发一个 isAdmin —— 单看它无法判断作用范围
                .userType(user.getUserType())
                .isAdmin(user.getIsAdmin())
                // 批量导入的账号带着统一初始密码，登录后必须先去改密码
                .mustChangePassword(user.mustChangePassword())
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

    /**
     * 公司被停用 / 合约到期 → 拒绝，并给出**能照着做**的原因。
     *
     * <p>判定本身在 {@link SysCompany#blockReason}（与 {@code UserDetailsServiceImpl}
     * 每个请求的判定是同一份逻辑）。这里再判一次是为二次验证那条路补位 ——
     * {@code verifyLogin} 的用户是从登录挑战记录里取出来的，没有经过
     * {@code loadUserByUsername}。
     *
     * <p>查不到公司（脏数据）时放行：数据问题不该表现成"客户登不进来"。
     */
    private void assertCompanyUsable(SysUser user) {
        if (user == null || user.getCompanyId() == null) {
            return;
        }
        SysCompany company = companyService.findById(user.getCompanyId());
        if (company == null) {
            return;
        }
        String reason = company.blockReason(LocalDateTime.now());
        if (reason != null) {
            log.info("登录被公司状态拦下（二次验证后）: userId={}, 原因={}", user.getId(), reason);
            throw new BizException(ErrorCode.FORBIDDEN, reason);
        }
    }
}
