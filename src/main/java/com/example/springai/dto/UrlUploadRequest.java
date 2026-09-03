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
     * 归属部门ID（必填）
     */
    private Long departmentId;

    /**
     * 可见性类型：1=本部门，2=全公司，3=指定部门（默认1）
     */
    private Integer visibleType = 1;

    /**
     * 是否公开（默认false）
     */
    private Boolean isPublic = false;
}