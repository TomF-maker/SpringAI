package com.example.springai.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 公司详情里的一个文档（只取最近若干条，不是一个全量列表页）。 */
@Data
public class CompanyDocumentDTO {
    private Long id;
    private String title;
    private String fileName;
    private Integer chunkCount;
    /** 0=待处理 1=已向量化 2=失败。 */
    private Integer status;
    private LocalDateTime createdAt;
}
