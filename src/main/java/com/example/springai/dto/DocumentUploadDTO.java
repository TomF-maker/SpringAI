package com.example.springai.dto;

import lombok.Data;

@Data
public class DocumentUploadDTO {
    private String title;                // 文档标题（可选）

    /**
     * 归属部门。**已废弃**：部门维度去掉了（见 doc/商业化方案.md「A2. 去掉部门维度」），
     * 这个值不再被使用 —— 落库时统一写常量（{@code DocumentServiceImpl.resolveDepartmentId}）。
     * 字段留着是为了让既有调用方（前端旧页面、doc/ 下的入库脚本）不用改就能继续传。
     */
    private Long departmentId;

    /**
     * 所属客户公司（{@code sys_company.id}）。咨询隔离用。
     * <ul>
     *   <li>null = 通用方法论，所有客户可见；</li>
     *   <li>非 null = 该客户专属，仅本公司 + 伯伯咨询顾问可见。</li>
     * </ul>
     * 管理员上传时由表单选择；不传则视为通用。
     */
    private Long clientId;

    /**
     * 可见性类型（1=本部门 2=全公司 3=指定部门）。**已废弃**：随部门维度一起去掉，
     * 落库只为填满 {@code kb_document.visible_type} 这个 NOT NULL 列，不参与任何权限判断。
     * 字段留着是为了让既有调用方（doc/ 下的入库脚本）不用改就能继续传。
     */
    private Integer visibleType;

    /**
     * 是否公开。这是**真正**决定可见范围的两档之一（另一档是 {@link #clientId}）：
     * 公开=所有客户可见，内部=仅本公司可见。
     */
    private Boolean isPublic;
}