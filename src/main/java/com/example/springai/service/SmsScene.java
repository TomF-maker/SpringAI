package com.example.springai.service;

/**
 * 短信验证码场景。参与 Redis key（{@code verify:phone:<scene>:<phone>}），
 * 否则为"补绑"发的验证码可以被拿去"登录"用。
 */
public final class SmsScene {

    private SmsScene() {
    }

    public static final String REGISTER = "register";
    public static final String LOGIN = "login";
    public static final String BIND = "bind";
}
