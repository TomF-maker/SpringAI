package com.example.springai.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 抓来的一条新闻。建表语句见 {@code doc/schema.sql} 的 2026-09-15 新闻段。
 *
 * <p>这张表存在的首要理由是<b>去重</b>：新闻源都是"最近 N 条"的滚动列表，
 * 同一条今天在、明天还在。没有 {@link #dedupKey} 的唯一索引，每天那封邮件
 * 就是同一批内容 —— 而且因为是定时抓取、没人核对，重复会一直持续下去。
 *
 * <p>{@link #pushedAt} 是第二个关键字段：{@code dedupKey} 管"同一条别存两遍"，
 * 它管"同一条别发两遍"。
 */
@Data
@TableName("kb_news")
public class KbNews {

    /** 聚合接口（AI HOT）。条目自带 id、评分、分类、入选理由。 */
    public static final String SOURCE_API = "API";
    /** RSS 订阅源（量子位 / InfoQ / 开源中国）。没有评分和分类。 */
    public static final String SOURCE_RSS = "RSS";

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 去重键。<b>唯一索引建在它上面</b>，抓取时靠 {@code INSERT IGNORE} 撞键跳过。
     *
     * <p>API 条目直接用它自己的 id；RSS 条目用 link 的 SHA-256（没有稳定的 id，
     * 而标题会改、link 相对稳定）。
     */
    private String dedupKey;

    private String sourceType;
    /** 来源名，如「量子位」「OpenAI：官网动态」。邮件里按来源统计用。 */
    private String sourceName;
    /** tip / ai-products / ai-models / industry / paper；RSS 源为 null。 */
    private String category;
    private String title;
    /** RSS 的 description 带 HTML 标签，入库前已清洗成纯文本。 */
    private String summary;
    private String url;
    /** 策展评分 0-100。RSS 源为 null —— 所以阈值过滤只作用于 API 条目。 */
    private Integer score;
    /** 入选理由，仅 API 提供。 */
    private String reason;
    /** 命中的关注关键词，逗号分隔。 */
    private String matchedKeywords;
    /** 源里给的发布时间。缺失时保持 null，**不要拿 fetchedAt 顶替** —— 那会让"过去 24 小时"的筛选失真。 */
    private LocalDateTime publishedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime fetchedAt;

    /** null = 还没进过邮件。发信后统一回写。 */
    private LocalDateTime pushedAt;
}
