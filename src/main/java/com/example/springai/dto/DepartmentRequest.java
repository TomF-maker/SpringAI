package com.example.springai.dto;

import lombok.Data;

@Data
public class DepartmentRequest {
    private Long id;           // 编辑时使用
    private Long parentId;
    private String deptName;
    private String deptCode;
    private Long leaderId;     // 负责人用户ID
    private String phone;
    private String email;
    private Integer sortOrder;
}