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

    // ---------- IP 归属地（BI 用） ----------
    // 四个字段一起看：ipAddress 一定在请求线程上就写好了（它只是字符串，零成本），
    // 但 country/province/city 是**异步补写**的 —— 归属地要外呼第三方，放在
    // record() 里会直接抬高流式问答的首字延迟，所以由 QuestionLogService 的
    // 专用线程池回填。没解析出来的就成了 NULL，报表上应显示为"未解析"，
    // 不要让前端把它当成"来自未知地区"。

    /** 完整客户端 IP。内网/取不到时为 null。注意这不是风控用的网段前缀。 */
    private String ipAddress;
    /** IP 归属国家。高德实现下恒为"中国"或 null（它只有国内数据）。 */
    private String ipCountry;
    /** IP 归属省份。BI 按省分组用这一列。 */
    private String ipProvince;
    /** IP 归属城市。 */
    private String ipCity;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
