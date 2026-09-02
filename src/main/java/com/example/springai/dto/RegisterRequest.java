package com.example.springai.dto;

import lombok.Data;

@Data
public class RegisterRequest {
    private String username;        // 用户名
    private String password;        // 密码
    private String email;           // 邮箱
    private String code;            // 邮箱验证码
    private String realName;        // 真实姓名（可选）
    private String phone;           // 手机号（可选）
}