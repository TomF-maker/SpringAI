package com.example.springai.dto;

import lombok.Data;

/** 在公司详情里把某个员工设为 / 取消"客户管理员"。 */
@Data
public class CompanyMemberAdminRequest {
    private Boolean isAdmin;
}
