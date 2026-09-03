package com.example.springai.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("kb_document_log")
public class KbDocumentLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long documentId;
    private Long userId;
    private String action;      // UPLOAD, VIEW, DELETE, DOWNLOAD
    private String ipAddress;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}