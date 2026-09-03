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