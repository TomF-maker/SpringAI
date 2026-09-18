package com.example.springai.dto;

import lombok.Data;

/**
 * URL上传文档请求DTO
 */
@Data
public class UrlUploadRequest {
    /**
     * 文件URL（必填）
     */
    private String url;

    /**
     * 文档标题（可选，不填则从文件名自动提取）
     */
    private String title;

    /**
     * 归属部门ID。**已废弃**：部门维度去掉了（见 doc/商业化方案.md「A2. 去掉部门维度」），
     * 值不再被使用，落库统一写常量。字段留着只是让既有调用方不用改。
     */
    private Long departmentId;

    /**
     * 所属客户公司（sys_company.id）；null = 通用方法论，所有客户可见
     */
    private Long clientId;

    /**
     * 可见性类型（1=本部门 2=全公司 3=指定部门）。**已废弃**：值不再参与权限判断，
     * 只为填满 {@code kb_document.visible_type} 这个 NOT NULL 列。留着让既有调用方不用改。
     */
    private Integer visibleType = 1;

    /**
     * 是否公开（默认false）：公开=所有客户可见，内部=仅本公司可见
     */
    private Boolean isPublic = false;
}