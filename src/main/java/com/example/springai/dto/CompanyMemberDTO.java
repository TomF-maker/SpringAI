package com.example.springai.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 公司详情里的一个员工。 */
@Data
public class CompanyMemberDTO {
    private Long id;
    private String username;
    private String realName;
    private String email;
    private String phone;
    private Integer status;
    /** 1 = 该公司的客户管理员（能进客户看板）。 */
    private Integer isAdmin;
    private LocalDateTime lastLoginTime;
    /** 是否必须改初始密码（xlsx 导入后没改过的还是 1）。 */
    private Integer mustChangePassword;
}
