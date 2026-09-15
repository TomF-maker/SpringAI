package com.example.springai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.springai.entity.KbLoginLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface KbLoginLogMapper extends BaseMapper<KbLoginLog> {

    // ==================== 地域分布（大屏用） ====================
    // 与 KbQuestionLogMapper 里的三个同名查询结构一致，只是换了表。
    // 两张表都有 idx_llog_province / idx_qlog_created 可走，见 doc/schema.sql。
    //
    // 注意 kb_login_log 是**低频表**（每次成功登录一行），
    // kb_question_log 是高频表 —— 所以这两个大屏的数据量能差一个数量级，
    // 属正常，不要以为是查询漏了行。

    /** 按省分组。省名归一化在 Service 层做。 */
    @Select("""
            SELECT ip_province AS name, COUNT(*) AS count
            FROM kb_login_log
            WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY)
              AND ip_province IS NOT NULL AND ip_province <> ''
            GROUP BY ip_province
            ORDER BY count DESC
            """)
    List<Map<String, Object>> selectProvinceDistribution(@Param("days") int days);

    /** 按市分组，取前 N。 */
    @Select("""
            SELECT ip_city AS name, COUNT(*) AS count
            FROM kb_login_log
            WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY)
              AND ip_city IS NOT NULL AND ip_city <> ''
            GROUP BY ip_city
            ORDER BY count DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> selectCityDistribution(@Param("days") int days,
                                                     @Param("limit") int limit);

    /** 总量与未解析量。语义同 KbQuestionLogMapper.selectGeoCoverage。 */
    @Select("""
            SELECT COUNT(*) AS total,
                   SUM(CASE WHEN ip_province IS NULL OR ip_province = '' THEN 1 ELSE 0 END) AS unresolved
            FROM kb_login_log
            WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY)
            """)
    Map<String, Object> selectGeoCoverage(@Param("days") int days);
}
