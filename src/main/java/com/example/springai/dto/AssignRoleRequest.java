package com.example.springai.dto;

import lombok.Data;
import java.util.List;

@Data
public class AssignRoleRequest {
    private List<Long> roleIds;  // 角色ID列表
}