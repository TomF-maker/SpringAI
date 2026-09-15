package com.example.springai.service;

/**
 * 新闻抓取与每日推送。
 *
 * <p>{@link #fetchAndStore()} 和 {@link #sendDigest()} 刻意拆成两步，
 * 让"抓"和"发"可以分别触发（也分别可测）：
 * <ul>
 *   <li>抓取失败 → 一封邮件都不发，但库里已有的照旧；</li>
 *   <li>发信失败 → 那批新闻的 {@code pushed_at} <b>不被标记</b>，
 *       第二天会重新进入待发集合，不会凭空消失。</li>
 * </ul>
 */
public interface NewsDigestServiceI {

    /**
     * 跑一轮抓取：拉所有源 → 关键词标记 → 评分过滤 → {@code INSERT IGNORE} 入库。
     *
     * @return 本轮真正新增的条数（撞去重键的不计入）
     */
    int fetchAndStore();

    /**
     * 把还没推送过的新闻发成一封邮件。
     *
     * <p>收件人为空时只记日志、不发信，返回 0。
     *
     * @return 本次推送的条数；没有新内容或没发出去时返回 0
     */
    int sendDigest();
}
