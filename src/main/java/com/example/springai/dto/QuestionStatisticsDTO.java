package com.example.springai.dto;

import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * 提问数据统计。
 *
 * <p>所有"人均"类的指标都用同一个 {@code days} 窗口计算 ——
 * 否则"今天的提问数 ÷ 一个月的活跃用户"是没有任何意义的数字。
 *
 * <p>数据来源是 {@code kb_question_log}（单一数据源），
 * 若该表尚未创建，各字段会返回 0 并附带 {@link #unavailable} 标记。
 */
@Data
public class QuestionStatisticsDTO {

    /** 统计窗口（天）。 */
    private int days;

    /** true 表示埋点表不可用（多半是建表语句还没执行）。 */
    private boolean unavailable;

    // ---------- 总量 ----------
    private long totalQuestions;
    private long todayQuestions;
    private long totalConversations;
    private long activeUsers;
    /** 人均提问数 = totalQuestions / activeUsers，同一窗口。 */
    private double avgQuestionsPerUser;

    // ---------- 回答质量 ----------
    /** 本地知识库命中次数。 */
    private long localHitCount;
    /** 检索到文档并回答的次数。 */
    private long docAnswerCount;
    /** 知识库缺口：检索不到任何文档的次数。 */
    private long kbGapCount;
    /** 工具调用次数。 */
    private long toolCallCount;
    /** 处理异常次数。 */
    private long errorCount;
    /**
     * 文档命中率（百分比，0-100，保留一位小数）。
     * 定义为"检索到文档的回答数 / 需要检索的提问数"，
     * 本地知识库命中与工具调用不计入分母。
     */
    private double hitRate;
    /** 平均响应耗时（毫秒），只统计正常结束的请求。 */
    private long avgLatencyMs;

    // ---------- 明细 ----------
    private List<DailyQuestion> dailyQuestions = Collections.emptyList();
    private List<HotQuestionDTO> hotQuestions = Collections.emptyList();
}
