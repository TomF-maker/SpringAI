package com.example.springai.dto;

import lombok.Data;

@Data
public class UpdateStatusRequest {
    private Integer status;  // 0-禁用，1-启用
}