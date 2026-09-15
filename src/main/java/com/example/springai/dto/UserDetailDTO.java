package com.example.springai.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class UserDetailDTO {
    private Long id;
    private String username;
    private String email;
    private String phone;
    private String realName;
    private String avatar;
    private Long departmentId;
    private String departmentName;
    /** 所属公司 id（sys_company）。老用户为 null。 */
    private Long companyId;
    /** 公司名，从 sys_company 翻出来的。不是部门名 —— 两者不一致是正常的。 */
    private String companyName;
    /** 统一社会信用代码，18 位。老用户为 null。 */
    private String creditCode;
    private Integer userType;
    private Integer status;
    private Integer isAdmin;
    private String roleNames;
    private LocalDateTime lastLoginTime;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<Long> roleIds;
}