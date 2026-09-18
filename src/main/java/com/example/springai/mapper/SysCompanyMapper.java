package com.example.springai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.springai.dto.CompanyListDTO;
import com.example.springai.entity.SysCompany;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface SysCompanyMapper extends BaseMapper<SysCompany> {

    /**
     * 每个公司有多少人（只算启用中的账号），按人数倒序。
     *
     * <p>用 INNER JOIN：没有用户的公司不出现在榜上。公司表里理论上不该有这种空壳
     * （注册时 find-or-create，创建即挂人），出现的话多半是有人手工删过用户。
     *
     * <p>"未填写"（{@code company_id IS NULL}）的那部分**不在这个查询里**，
     * 由 {@link #countUsersWithoutCompany()} 单独取 —— 它是"没有公司"而不是"某个公司"，
     * 混进来会让各公司人数之和对不上总用户数。
     */
    @Select("""
            SELECT c.company_name AS name, COUNT(*) AS count
            FROM sys_user u
            JOIN sys_company c ON c.id = u.company_id
            WHERE u.status = 1
            GROUP BY c.id, c.company_name
            ORDER BY count DESC
            """)
    List<Map<String, Object>> selectCompanyUserCounts();

    /** 没有挂公司的启用账号数（本功能上线前注册的老用户）。 */
    @Select("SELECT COUNT(*) FROM sys_user WHERE status = 1 AND company_id IS NULL")
    long countUsersWithoutCompany();

    // ==================== 公司管理页（内部管理员用） ====================

    /**
     * 公司列表 + 三个用量数字，一条 SQL 取回。
     *
     * <p><b>为什么不用"查公司 → 逐家再查三个数"</b>：50~100 家客户就是 150+ 次查询，
     * 而这个页面每次打开、每次翻页都要跑一遍。三个子查询各自走索引，
     * 一次性取回比 N+1 便宜得多。
     *
     * <p>提问数的归属口径与客户看板**完全一致**（{@code user_id IN (公司员工)}）——
     * 见 {@code KbQuestionLogMapper} 里那段注释：不能改用会话的 client_id，
     * 存量会话那个字段是 NULL，历史数据会凭空消失。
     *
     * <p>员工数只算**启用中**的：那才是占席位的数量，列表上要和"席位上限"对得上。
     */
    @Select("""
            <script>
            SELECT c.id                AS id,
                   c.company_name      AS company_name,
                   c.credit_code       AS credit_code,
                   c.status            AS status,
                   c.contract_expire_at AS contract_expire_at,
                   c.seat_limit        AS seat_limit,
                   c.contract_note     AS contract_note,
                   (SELECT COUNT(*) FROM sys_user u
                     WHERE u.company_id = c.id AND u.status = 1) AS memberCount,
                   (SELECT COUNT(*) FROM kb_document d
                     WHERE d.client_id = c.id) AS documentCount,
                   (SELECT COUNT(*) FROM kb_question_log q
                     WHERE q.created_at >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY)
                       AND q.user_id IN (SELECT u2.id FROM sys_user u2 WHERE u2.company_id = c.id))
                     AS questions30d
            FROM sys_company c
            <where>
                <if test="keyword != null and keyword != ''">
                    (c.company_name LIKE CONCAT('%', #{keyword}, '%')
                     OR c.credit_code LIKE CONCAT('%', #{keyword}, '%'))
                </if>
            </where>
            ORDER BY c.id DESC
            LIMIT #{size} OFFSET #{offset}
            </script>
            """)
    List<CompanyListDTO> selectCompanyOverview(@Param("keyword") String keyword,
                                               @Param("days") int days,
                                               @Param("offset") long offset,
                                               @Param("size") long size);

    /** 与 {@link #selectCompanyOverview} 同一套 where 条件的总数（分页用）。 */
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM sys_company c
            <where>
                <if test="keyword != null and keyword != ''">
                    (c.company_name LIKE CONCAT('%', #{keyword}, '%')
                     OR c.credit_code LIKE CONCAT('%', #{keyword}, '%'))
                </if>
            </where>
            </script>
            """)
    long countCompanies(@Param("keyword") String keyword);

    /**
     * 启动探针：确认到期提醒用的两列在不在（列名写错/DDL 没执行时只查这一列就够）。
     *
     * <p>为什么要有它：这两列只被每天一次的定时任务用到 —— 缺了它们应用照常启动、
     * 页面照常打开，唯一症状是"提醒一直不来"，而那种故障要等到有人问
     * "怎么没收到到期提醒" 才会被发现。探针把它变成开机可见的一行红字。
     *
     * <p>表为空时返回 null（不抛异常），所以空库不会误报。
     */
    @Select("SELECT remind_30_sent_at FROM sys_company LIMIT 1")
    LocalDateTime probeReminderColumns();
}
