package com.example.springai.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    /**
     * 短信总开关。注册页要据此决定渲染「手机号 + 短信验证码」还是
     * 「邮箱 + 邮箱验证码」表单，所以在服务端就把标志位传下去，
     * 避免前端先渲染错表单再切换。
     */
    @Value("${app.sms.enabled:false}")
    private boolean smsEnabled;

    @GetMapping("/")
    public String root() {
        return "redirect:/login";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/register")
    public String register(Model model) {
        model.addAttribute("smsEnabled", smsEnabled);
        return "register";
    }

    /**
     * 忘记密码：邮箱验证码重置。
     *
     * <p>不需要 smsEnabled —— 这条流程走的是**邮箱**验证码，和短信总开关无关。
     * 但注意：短信开启时注册只要求手机号、邮箱变成可选，那类没填邮箱的账号
     * 走不了这条路，只能找管理员重置。
     */
    @GetMapping("/forgot-password")
    public String forgotPassword() {
        return "forgot-password";
    }

    @GetMapping("/chat")
    public String chat() {
        return "chat";
    }

    @GetMapping("/users")
    public String users() {
        return "users";
    }

    @GetMapping("/documents")
    public String documents() {
        return "documents";
    }

    @GetMapping("/departments")
    public String departments() {
        return "departments";
    }

    @GetMapping("/profile")
    public String profile() {
        return "profile";
    }

    @GetMapping("/dashboard")
    public String dashboard() {
        return "dashboard";
    }

    /**
     * 地域大屏（登录 / 提问分别来自哪个省、市）。
     *
     * <p>全屏深色页，刻意不带 sidebar/topbar 片段，也不引 app.css / app.js ——
     * 那两个是给带侧边栏的后台页面用的，在全屏投屏页上既用不上又可能打架。
     */
    @GetMapping("/screen")
    public String screen() {
        return "screen";
    }

    @GetMapping("/history")
    public String history() {
        return "history";
    }

    @GetMapping("/feedback-review")
    public String feedbackReview() {
        return "feedback-review";
    }
}