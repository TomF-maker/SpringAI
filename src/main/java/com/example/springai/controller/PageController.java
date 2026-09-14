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

    @GetMapping("/history")
    public String history() {
        return "history";
    }

    @GetMapping("/feedback-review")
    public String feedbackReview() {
        return "feedback-review";
    }
}