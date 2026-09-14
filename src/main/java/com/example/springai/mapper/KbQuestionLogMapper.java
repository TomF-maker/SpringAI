package com.example.springai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.springai.entity.KbQuestionLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 提问埋点查询。聚合写法沿用 {@code KbDocumentMapper} 的
 * {@code @Select → List<Map<String,Object>>} 约定。
 *
 * <p>注意：这些查询依赖 {@code kb_question_log} 表，建表前调用会抛异常，
 * 由上层 {@code QuestionStatsService} 统一兜底。
 */
@Mapper
public interface KbQuestionLogMapper extends BaseMapper<KbQuestionLog> {

    /** 提问总量 / 活跃用户（提问过的人）/ 会话数。 */
    @Select("""
            SELECT COUNT(*) AS total,
                   COUNT(DISTINCT user_id) AS activeUsers,
                   COUNT(DISTINCT conversation_id) AS conversations
            FROM kb_question_log
            WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY)
            """)
    Map<String, Object> selectTotals(@Param("days") int days);

    /** 今日提问量。 */
    @Select("SELECT COUNT(*) FROM kb_question_log WHERE DATE(created_at) = CURDATE()")
    long selectTodayCount();

    /** 按天趋势，附带当天去重活跃用户数。 */
    @Select("""
            SELECT DATE(created_at) AS date,
                   COUNT(*) AS count,
                   COUNT(DISTINCT user_id) AS activeUsers
            FROM kb_question_log
            WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY)
            GROUP BY DATE(created_at)
            ORDER BY date ASC
            """)
    List<Map<String, Object>> selectDailyQuestions(@Param("days") int days);

    /** 各回答类型分布，用于算命中率与知识库缺口。 */
    @Select("""
            SELECT hit_type, COUNT(*) AS count
            FROM kb_question_log
            WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY)
            GROUP BY hit_type
            """)
    List<Map<String, Object>> selectHitTypeDistribution(@Param("days") int days);

    /**
     * 热门问题 Top N。
     *
     * <p>按归一化后的问题分组，展示时取原始问题的一个样本。
     * 归一化不引入中文分词：分词给的是"词频"而不是"热门问题"，
     * 且会带来几百 MB 的模型依赖，见 QuestionNormalizer 里的说明。
     */
    @Select("""
            SELECT question_norm AS questionNorm,
                   MIN(question) AS question,
                   COUNT(*) AS count,
                   MAX(created_at) AS lastAskedAt
            FROM kb_question_log
            WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY)
              AND question_norm IS NOT NULL AND question_norm <> ''
            GROUP BY question_norm
            ORDER BY count DESC, lastAskedAt DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> selectHotQuestions(@Param("days") int days, @Param("limit") int limit);

    /** 平均耗时（只统计正常结束的行）。 */
    @Select("""
            SELECT AVG(latency_ms) AS avgLatency, COUNT(*) AS sampled
            FROM kb_question_log
            WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY)
              AND latency_ms IS NOT NULL
            """)
    Map<String, Object> selectLatencyStats(@Param("days") int days);

    /** 工具调用次数。 */
    @Select("""
            SELECT COUNT(*) FROM kb_question_log
            WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY)
              AND hit_type = 'TOOL'
            """)
    long selectToolCallCount(@Param("days") int days);
}
