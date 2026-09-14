package com.example.springai.dto;

import lombok.Data;

/**
 * 异地登录/补绑手机号的验证码兑换请求。
 *
 * <p>只接受 challengeId，**不接受前端传来的 userId / username / phone** ——
 * 用户身份只从 Redis 里的挑战记录读取，避免越权。
 */
@Data
public class VerifyLoginRequest {
    private String challengeId;
    private String code;
    /** BIND 场景下用于补绑的新手机号；VERIFY 场景留空，用账号已绑定的号码。 */
    private String phone;
}
