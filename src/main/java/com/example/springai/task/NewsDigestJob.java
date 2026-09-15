package com.example.springai.task;

import com.example.springai.config.NewsProperties;
import com.example.springai.mapper.KbNewsMapper;
import com.example.springai.service.NewsDigestServiceI;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 每天早 8 点（北京时间）抓一轮新闻并给配置的收件人发一封摘要。
 *
 * <p>抓取和发送分成两次调用（{@link NewsDigestServiceI#fetchAndStore()} 然后
 * {@code sendDigest()}），而不是揉成一个方法 —— 这样日志里能分清
 * "今天没有新新闻"和"抓取挂了"，排查时不用猜。
 *
 * <p>失败语义（和会员清理任务相反，那边失败是无害的，这边不是）：
 * <ul>
 *   <li>抓取失败 → 不影响已入库的，也不发信；</li>
 *   <li>发信失败 → {@code pushed_at} 不标记，第二天重新进入待发集合。</li>
 * </ul>
 * 所以"今天没收到邮件"通常不是新闻没了，而是发信或抓取失败了 —— 看日志。
 */
@Slf4j
@Component
public class NewsDigestJob {

    private static final String LOCK_KEY = "news:digest:job";

    @Autowired
    private NewsDigestServiceI newsDigestService;

    @Autowired
    private KbNewsMapper newsMapper;

    @Autowired
    private NewsProperties props;

    @Autowired
    private StringRedisTemplate redis;

    /**
     * 启动探针：确认表和列都在。
     *
     * <p>{@code kb_news} 是新建表，且不在任何请求路径上 —— 缺了它应用照常跑，
     * 只是每天 8 点的任务失败。那种故障如果不主动报，要等到有人问
     * "怎么没收到邮件"才会被发现。这条探针把它变成开机可见的一行红字。
     */
    @PostConstruct
    void verifySchema() {
        try {
            newsMapper.probe();
            log.info("📍 新闻表 kb_news 就绪");
        } catch (Exception e) {
            log.error("❌ kb_news 不可用，每日新闻推送将无法工作。"
                    + "请先执行 doc/schema.sql 里的 2026-09-15 新闻段建表语句: {}", e.getMessage());
        }
    }

    /**
     * <b>必须显式写 {@code zone}</b>：不写就按 JVM 默认时区，而生产是云上 Linux，
     * 默认常常是 UTC —— 邮件会在北京时间下午 4 点发出去。
     */
    @Scheduled(cron = "${app.news.cron:0 0 8 * * ?}", zone = "Asia/Shanghai")
    public void run() {
        if (!props.isEnabled()) {
            log.debug("新闻推送已关闭（app.news.enabled=false）");
            return;
        }

        // 单飞键：现在单实例用不上，多实例时每个实例都会跑一遍、给收件人发多封。
        // TTL 30 分钟且**不在 finally 里删** —— 删掉的话第一个实例发完，
        // 第二个紧接着会再发一封。任务中途挂了也没关系，TTL 到期自然恢复。
        Boolean acquired = redis.opsForValue()
                .setIfAbsent(LOCK_KEY, "1", 30, TimeUnit.MINUTES);
        if (!Boolean.TRUE.equals(acquired)) {
            log.info("新闻推送已在其它实例执行，本次跳过");
            return;
        }

        long start = System.currentTimeMillis();
        try {
            int stored = newsDigestService.fetchAndStore();
            int pushed = newsDigestService.sendDigest();
            log.info("📰 每日新闻任务完成：新增 {} 条，推送 {} 条，耗时 {}ms",
                    stored, pushed, System.currentTimeMillis() - start);
        } catch (Throwable t) {
            // 定时任务里抛异常会被静默丢掉（除非配了 ErrorHandler），必须自己兜住。
            // 注意**不删单飞键**：留下它可以让重试发生在 30 分钟后而不是立刻，
            // 避免源站短暂故障时反复重试把配额打满。
            log.error("❌ 每日新闻任务失败", t);
        }
    }
}
