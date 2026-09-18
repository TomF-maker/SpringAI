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

    /**
     * 用户类型（1=内部 2=外部），前端侧边栏据此判断要不要显示「客户看板」。
     *
     * <p>连同下面的 {@code isAdmin} 一起下发：客户管理员是"外部用户 + isAdmin"，
     * 前端只靠 username === 'admin' 那种硬编码判断认不出来。
     */
    private Integer userType;

    /** 是否管理员（0/1）。单独看没有意义，作用范围由 userType 决定。 */
    private Integer isAdmin;

    /**
     * true 表示必须先改初始密码才能使用平台（xlsx 批量导入的账号）。
     *
     * <p>前端登录后据此直接跳到改密页。但**前端跳转只是引导** ——
     * 真正的约束在 {@code PasswordChangeGuardFilter}：那些账号在改密前
     * 调任何接口都会拿到 403 + {@code PWD_CHANGE_REQUIRED}，
     * 所以绕过跳转（直接敲 URL 或用 curl）也照样用不了平台。
     */
    private Boolean mustChangePassword;

    /** true 表示密码已通过，但需要手机验证码二次验证，此时 token 为 null。 */
    private Boolean requirePhoneVerify;

    /** 挑战场景：VERIFY = 异地登录校验，BIND = 首次登录需补绑手机号。 */
    private String scene;

    /** 脱敏手机号（138****8000）。BIND 场景下为 null。 */
    private String maskedPhone;

    /** 挑战 id，用于调用 /api/auth/verify-login 兑换 token。 */
    private String challengeId;
}
