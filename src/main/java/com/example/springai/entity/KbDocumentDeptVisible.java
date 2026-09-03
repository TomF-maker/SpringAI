package com.example.springai.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("kb_document_dept_visible")
public class KbDocumentDeptVisible {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long documentId;
    private Long departmentId;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}