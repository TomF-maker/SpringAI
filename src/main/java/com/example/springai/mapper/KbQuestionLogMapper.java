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

    // ==================== 地域分布（大屏用） ====================
    // 三个查询合起来才是完整的一张图：分布 + 总量 + 未解析量。
    // **未解析量必须一起查**：高德覆盖不全，不显示它的话会看到"广东占 40%"
    // 却不知道剩下 60% 是境外还是压根没解析出来。

    /** 按省分组。省名归一化在 Service 层做（SQL 里不认"广东省"和"广东"是一回事）。 */
    @Select("""
            SELECT ip_province AS name, COUNT(*) AS count
            FROM kb_question_log
            WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY)
              AND ip_province IS NOT NULL AND ip_province <> ''
            GROUP BY ip_province
            ORDER BY count DESC
            """)
    List<Map<String, Object>> selectProvinceDistribution(@Param("days") int days);

    /** 按市分组，取前 N。 */
    @Select("""
            SELECT ip_city AS name, COUNT(*) AS count
            FROM kb_question_log
            WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY)
              AND ip_city IS NOT NULL AND ip_city <> ''
            GROUP BY ip_city
            ORDER BY count DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> selectCityDistribution(@Param("days") int days,
                                                     @Param("limit") int limit);

    /**
     * 总量与未解析量。
     *
     * <p>"未解析"包含两种：IP 是内网/取不到（本来就不该解析），
     * 以及归属地还没补写完（异步回填，或者查询时刚好没成功）。
     * 从报表角度它们是一回事 —— 都是"这次提问没有地域信息"。
     */
    @Select("""
            SELECT COUNT(*) AS total,
                   SUM(CASE WHEN ip_province IS NULL OR ip_province = '' THEN 1 ELSE 0 END) AS unresolved
            FROM kb_question_log
            WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY)
            """)
    Map<String, Object> selectGeoCoverage(@Param("days") int days);
}
