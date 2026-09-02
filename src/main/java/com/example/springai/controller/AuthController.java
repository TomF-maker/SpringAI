package com.example.springai.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.springai.dto.LoginRequest;
import com.example.springai.dto.LoginResponse;
import com.example.springai.dto.RegisterRequest;
import com.example.springai.dto.SendCodeRequest;
import com.example.springai.entity.SysRole;
import com.example.springai.entity.SysUser;
import com.example.springai.entity.SysUserRole;
import com.example.springai.mapper.SysRoleMapper;
import com.example.springai.mapper.SysUserMapper;
import com.example.springai.mapper.SysUserRoleMapper;
import com.example.springai.service.EmailServiceI;
import com.example.springai.service.VerificationCodeServiceI;
import com.example.springai.utils.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

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
    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        // 1. 认证
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 2. 从认证信息获取用户
        String username = authentication.getName();
        SysUser user = userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SysUser>()
                        .eq("username", username)
                        .or()
                        .eq("email", username)
        );
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        // 3. 生成 JWT
        String token = jwtUtils.generateToken(username);

        // 4. 更新最后登录时间
        user.setLastLoginTime(LocalDateTime.now());
        userMapper.updateById(user);

        // 5. 返回
        return new LoginResponse(token, user.getUsername(), user.getRealName(), user.getId());
    }

    /**
     * 发送验证码
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

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody RegisterRequest request) {
        Map<String, Object> response = new HashMap<>();

        // 1. 校验验证码
        boolean valid = verificationCodeService.verify(request.getEmail(), request.getCode());
        if (!valid) {
            response.put("code", 400);
            response.put("message", "验证码错误或已过期");
            return response;
        }

        // 2. 检查用户名是否已存在
        SysUser existingByUsername = userMapper.selectOne(
                new QueryWrapper<SysUser>().eq("username", request.getUsername())
        );
        if (existingByUsername != null) {
            response.put("code", 400);
            response.put("message", "用户名已被占用");
            return response;
        }

        // 3. 检查邮箱是否已注册
        SysUser existingByEmail = userMapper.selectOne(
                new QueryWrapper<SysUser>().eq("email", request.getEmail())
        );
        if (existingByEmail != null) {
            response.put("code", 400);
            response.put("message", "邮箱已被注册");
            return response;
        }

        // 4. 创建新用户
        SysUser user = new SysUser();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setEmail(request.getEmail());
        user.setRealName(request.getRealName() != null ? request.getRealName() : request.getUsername());
        user.setPhone(request.getPhone());
        user.setUserType(1);           // 默认内部员工
        user.setStatus(1);             // 默认启用
        user.setIsAdmin(0);            // 默认非管理员
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());

        userMapper.insert(user);

        // 5. 分配默认角色（USER角色，role_id = 3）
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
}