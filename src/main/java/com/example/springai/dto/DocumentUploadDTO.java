package com.example.springai.dto;

import lombok.Data;
import java.util.List;

@Data
public class DocumentUploadDTO {
    private String title;                // 文档标题（可选）
    private Long departmentId;           // 归属部门（必填）

    /**
     * 所属客户公司（{@code sys_company.id}）。咨询隔离用。
     * <ul>
     *   <li>null = 通用方法论，所有客户可见；</li>
     *   <li>非 null = 该客户专属，仅本公司 + 伯伯咨询顾问可见。</li>
     * </ul>
     * 管理员上传时由表单选择；不传则视为通用。
     */
    private Long clientId;

    private Integer visibleType;         // 1=本部门 2=全公司 3=指定部门
    private Boolean isPublic;            // 是否公开
    private List<Long> visibleDepartmentIds; // 指定可见部门ID列表（visibleType=3时使用）
}