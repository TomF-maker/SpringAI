package com.example.springai.dto;

import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * 客户公司看板数据（客户管理员看自己的公司）。
 *
 * <p>与内部看板的 {@link QuestionStatisticsDTO} 刻意分开，不做成"加个 clientId 参数
 * 复用同一个 DTO"：两个看板回答的是不同问题 ——
 * 内部关心"知识库缺口、哪类问题答不上来"（要据此补文档）；
 * 客户关心"我的员工用得多不多、问得最多的是什么"（要据此判断这个东西值不值）。
 * 混成一个 DTO 就会被迫给客户也返回一堆他们看不懂、也不该看到的指标。
 *
 * <p><b>所有数字都只统计本公司员工</b>（{@code kb_question_log.user_id} 落在
 * {@code sys_user.company_id = 本客户} 的那些行）。查询侧
 * {@code KbQuestionLogMapper} 里统一带这个条件，本 DTO 不承担隔离职责。
 */
@Data
public class ClientAdminStatsDTO {

    /** 统计窗口（天）。 */
    private int days;

    /** true 表示埋点表不可用（多半是建表语句还没执行）。 */
    private boolean unavailable;

    /** 本公司名称，供页面标题展示。 */
    private String companyName;

    // ---------- 总量 ----------
    /** 窗口内本公司提问总数。 */
    private long totalQuestions;
    /** 今日提问数。 */
    private long todayQuestions;
    /** 窗口内提过问的员工数（去重）。 */
    private long activeUsers;
    /** 窗口内本公司产生的会话数。 */
    private long totalConversations;
    /** 人均提问数 = totalQuestions / activeUsers，同一窗口。 */
    private double avgQuestionsPerUser;

    // ---------- 回答质量 ----------
    /** 检索到文档并回答的次数。 */
    private long docAnswerCount;
    /** 知识库缺口：本公司的问题里有几次没检索到任何文档。 */
    private long kbGapCount;
    /**
     * 文档命中率（百分比，0-100，保留一位小数）。
     *
     * <p>分母是"需要检索的提问"（DOC + MISS），与内部看板口径一致 ——
     * 本地知识库命中是预置答案、工具调用不检索，算进去会让这个数字虚高。
     */
    private double hitRate;

    // ---------- 明细 ----------
    /** 每日提问趋势。 */
    private List<DailyQuestion> dailyQuestions = Collections.emptyList();
    /** 热门问题 Top N。 */
    private List<HotQuestionDTO> hotQuestions = Collections.emptyList();
    /**
     * 员工活跃榜 Top N，{@code name} 是员工姓名（取不到时是用户名）。
     *
     * <p>复用 {@link NameCount} 而不是新建一个只有两个字段的 DTO ——
     * 它就是这个形状：一行"名字 → 数量"。
     */
    private List<NameCount> activeUserRank = Collections.emptyList();
}
