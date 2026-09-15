package com.example.springai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.springai.entity.KbNews;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface KbNewsMapper extends BaseMapper<KbNews> {

    /**
     * 插入一条新闻，<b>撞 dedup_key 唯一索引时静默跳过</b>。
     *
     * <p>刻意不用 MyBatis-Plus 的 {@code insert} —— 它撞唯一键会抛
     * {@code DuplicateKeyException}，而"这条昨天已经抓过了"是<b>每时每刻都在发生的正常情况</b>，
     * 用异常来表达它意味着每天几百次异常，既吵又慢（异常栈的构造成本不低）。
     *
     * <p>{@code INSERT IGNORE} 顺带把其它告警也降成 warning（比如字段超长被截断），
     * 所以调用方<b>不能靠返回值判断"是不是重复"</b>：返回 0 只说明没插进去，
     * 可能是重复，也可能是别的问题。真正区分"新增"靠 {@link #selectPending}。
     *
     * <p>时间戳在这里显式传参而不是用 {@code NOW()}：项目里所有业务时间都走
     * {@code AppTime}（Asia/Shanghai），混用数据库时钟和 JVM 时钟迟早对不上。
     * 也正因为是手写 SQL，{@code MyMetaObjectHandler} 的自动填充不会生效，必须自己传。
     */
    @Insert("""
            INSERT IGNORE INTO kb_news
              (dedup_key, source_type, source_name, category, title, summary, url,
               score, reason, matched_keywords, published_at, fetched_at)
            VALUES
              (#{dedupKey}, #{sourceType}, #{sourceName}, #{category}, #{title}, #{summary}, #{url},
               #{score}, #{reason}, #{matchedKeywords}, #{publishedAt}, #{fetchedAt})
            """)
    int insertIgnore(KbNews news);

    /**
     * 还没进过邮件的新闻，评分高的在前。
     *
     * <p>这就是"这次真正新增的"集合 —— {@code INSERT IGNORE} 拦掉的那些不在里面。
     * MySQL 的 {@code ORDER BY ... DESC} 把 NULL 排在最后，所以 RSS 条目
     * （没有评分）自然落在 API 条目之后，正好是想要的顺序。
     */
    @Select("""
            SELECT id, dedup_key, source_type, source_name, category, title, summary, url,
                   score, reason, matched_keywords, published_at, fetched_at, pushed_at
            FROM kb_news
            WHERE pushed_at IS NULL
            ORDER BY score DESC, published_at DESC, id DESC
            """)
    List<KbNews> selectPending();

    /**
     * 标记为已推送。
     *
     * <p><b>调用方必须保证 {@code ids} 非空</b> —— 空集合会拼出 {@code IN ()}，
     * 那是 SQL 语法错误。
     */
    @Update("""
            <script>
            UPDATE kb_news SET pushed_at = #{pushedAt}
            WHERE id IN
            <foreach item="id" collection="ids" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int markPushed(@Param("ids") List<Long> ids, @Param("pushedAt") LocalDateTime pushedAt);

    /**
     * 启动探针：确认表和列都在。
     *
     * <p>用显式列清单而不是 {@code SELECT *} 或 {@code COUNT(*)}——
     * 后两者在"表在但列缺"（DDL 只执行了一半）时查不出来，而那正是要防的情况。
     */
    @Select("""
            SELECT id, dedup_key, source_type, source_name, category, title, summary, url,
                   score, reason, matched_keywords, published_at, fetched_at, pushed_at
            FROM kb_news LIMIT 1
            """)
    List<KbNews> probe();
}
