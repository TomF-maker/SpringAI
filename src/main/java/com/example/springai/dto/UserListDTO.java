package com.example.springai.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class UserListDTO {
    private Long id;
    private String username;
    private String email;
    private String realName;
    private String departmentName;
    private Integer userType;
    private Integer status;
    private String roleNames;
    private LocalDateTime createdAt;
}