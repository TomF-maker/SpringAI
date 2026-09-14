package com.example.springai.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 提问埋点。数据看板的提问类 KPI 全部来源于这张表（单一数据源）。
 *
 * <p>建表语句见 {@code doc/schema.sql}。本项目没有自动迁移，
 * 表不存在时 {@code QuestionLogService} 会降级为记日志而不是让业务崩掉。
 */
@Data
@TableName("kb_question_log")
public class KbQuestionLog {

    /** 回答类型。 */
    public static final String HIT_LOCAL = "LOCAL";   // 命中本地知识库
    public static final String HIT_DOC = "DOC";       // 检索到文档并回答
    public static final String HIT_MISS = "MISS";     // 未检索到相关文档（知识库缺口）
    public static final String HIT_TOOL = "TOOL";     // 走了工具调用
    public static final String HIT_ERROR = "ERROR";   // 处理异常

    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_ERROR = "ERROR";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String conversationId;
    private String question;
    /** 归一化后的问题，用于热门问题 Top-N 分组。 */
    private String questionNorm;
    private String hitType;
    private Integer retrievedCount;
    private String toolName;
    /** 为 null 表示流没有正常结束（客户端断开等）。 */
    private Long latencyMs;
    private String status;
    private String source;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
