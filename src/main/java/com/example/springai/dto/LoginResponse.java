package com.example.springai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录结果。
 *
 * <p>注意这是一个"二选一"的响应：正常登录时 {@code token} 有值；
 * 需要手机验证码二次验证时 {@code token} 为 null、{@code requirePhoneVerify} 为 true。
 *
 * <p><b>前端必须先判断 {@code requirePhoneVerify}</b>，否则会把字符串 "undefined"
 * 当成 token 存进 localStorage 然后跳转。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    private String token;
    private String username;
    private String realName;
    private Long userId;

    /** true 表示密码已通过，但需要手机验证码二次验证，此时 token 为 null。 */
    private Boolean requirePhoneVerify;

    /** 挑战场景：VERIFY = 异地登录校验，BIND = 首次登录需补绑手机号。 */
    private String scene;

    /** 脱敏手机号（138****8000）。BIND 场景下为 null。 */
    private String maskedPhone;

    /** 挑战 id，用于调用 /api/auth/verify-login 兑换 token。 */
    private String challengeId;
}
