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

    /** 所属公司名称。<b>必填</b>。用户可以自报，不必先在部门表里存在。 */
    private String companyName;
    /** 统一社会信用代码。<b>必填</b>，18 位含校验位，全局唯一。校验见 common.CreditCode */
    private String creditCode;
}