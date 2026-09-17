package com.example.springai.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("kb_document")
public class KbDocument {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String fileName;
    private String filePath;
    private Long fileSize;
    private String fileType;
    private Long uploaderId;
    private Long departmentId;

    /**
     * 所属客户公司（{@code sys_company.id}）。咨询隔离的核心字段。
     *
     * <p>语义：
     * <ul>
     *   <li><b>NULL = 通用方法论</b>：所有客户公司都能检索到（咨询行业通用框架、
     *       公开模板等）。存量文档（本字段上线前已入库的）默认就是 NULL，
     *       视为通用，不强制回填。</li>
     *   <li><b>非 NULL = 该客户专属</b>：只有该客户公司的员工（按
     *       {@code sys_user.company_id} 匹配）和伯伯公司的咨询顾问（内部用户）
     *       能检索到。A 客户付费做的咨询方案，B 客户绝不能搜到 —— 这是
     *       咨询行业不可触碰的铁律。</li>
     * </ul>
     *
     * <p>注意：本字段上线后，Qdrant 的 payload 里会带上 {@code client_id}，
     * 检索时由 {@code RagServiceImpl.buildQdrantFilter} 按"自己公司 OR 通用"
     * 过滤。已入库但没带 client_id 的向量，payload 里缺这个 key，
     * Qdrant 的 match 条件命中不到它们 —— 会被当成"通用"漏过去。
     * 想严格隔离存量数据时，需要重新向量化一次（见 schema.sql 的迁移说明）。
     */
    private Long clientId;

    private Integer visibleType;   // 1=本部门 2=全公司 3=指定部门
    private Integer isPublic;      // 0=内部 1=公开
    private Integer status;        // 0=待处理 1=已向量化 2=失败
    private Integer chunkCount;
    private Integer viewCount;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}