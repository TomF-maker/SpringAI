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
    private Integer userType;
    private Integer status;
    private Integer isAdmin;
    private String roleNames;
    private LocalDateTime lastLoginTime;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<Long> roleIds;
}