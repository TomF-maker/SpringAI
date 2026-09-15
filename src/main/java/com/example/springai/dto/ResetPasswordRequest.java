package com.example.springai.dto;

import lombok.Data;

/**
 * 忘记密码：用邮箱验证码重置密码。
 *
 * <p>验证码复用 {@code /api/auth/send-code} 发的那套（Redis {@code verify:code:<email>}，
 * 5 分钟、一次性）。<b>验证码天然绑定邮箱</b> —— 重置时用邮箱反查账号，
 * 所以拿到别人邮箱的验证码也没用，不存在跨账号风险。
 */
@Data
public class ResetPasswordRequest {

    /** 注册时用的邮箱。账号就是靠它反查的。 */
    private String email;

    /** 邮箱验证码。 */
    private String code;

    /** 新密码。 */
    private String newPassword;
}
