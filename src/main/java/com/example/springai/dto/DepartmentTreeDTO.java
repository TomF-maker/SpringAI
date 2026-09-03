package com.example.springai.dto;

import lombok.Data;
import java.util.List;

@Data
public class DepartmentTreeDTO {
    private Long id;
    private String deptName;
    private String deptCode;
    private Long parentId;
    private String leaderName;
    private String phone;
    private String email;
    private Integer sortOrder;
    private List<DepartmentTreeDTO> children;
}