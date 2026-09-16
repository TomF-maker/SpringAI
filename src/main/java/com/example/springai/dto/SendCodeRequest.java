package com.example.springai.dto;

import lombok.Data;

@Data
public class SendCodeRequest {
    private String email;

    /**
     * 图形验证码的 id 与用户输入。
     *
     * <p><b>这两个字段是必填的</b>：服务端先验图形验证码再走限流与发信（见
     * {@code AuthController.sendCode} 的注释）。少传任何一个都会被判为"图形验证码错误或已过期"，
     * 前端三个发码入口都必须带上。
     */
    private String captchaId;
    private String captchaCode;
}
