package com.example.springai.dto;

import lombok.Data;

@Data
public class UpdateUserInfoRequest {
    private String realName;
    private String phone;
    private String email;
}