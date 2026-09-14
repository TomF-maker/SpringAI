package com.example.springai.dto;

import lombok.Data;

@Data
public class SendSmsCodeRequest {
    /** 注册场景必填；BIND 场景填要补绑的号码。登录挑战场景由 challengeId 解析，不用填。 */
    private String phone;

    /** 场景：register / login / bind，见 SmsScene。默认 register。 */
    private String scene;

    /**
     * 登录挑战 id。填了它就从挑战里解析接收号码（VERIFY 用账号已绑定的号），
     * 服务端不需要把完整手机号回传给前端。
     */
    private String challengeId;
}
