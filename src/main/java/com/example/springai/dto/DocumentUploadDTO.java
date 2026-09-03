package com.example.springai.dto;

import lombok.Data;
import java.util.List;

@Data
public class DocumentUploadDTO {
    private String title;                // 文档标题（可选）
    private Long departmentId;           // 归属部门（必填）
    private Integer visibleType;         // 1=本部门 2=全公司 3=指定部门
    private Boolean isPublic;            // 是否公开
    private List<Long> visibleDepartmentIds; // 指定可见部门ID列表（visibleType=3时使用）
}