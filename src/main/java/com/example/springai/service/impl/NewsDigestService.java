package com.example.springai.service.impl;

import com.example.springai.common.AppTime;
import com.example.springai.config.NewsProperties;
import com.example.springai.entity.KbNews;
import com.example.springai.mapper.KbNewsMapper;
import com.example.springai.service.EmailServiceI;
import com.example.springai.service.NewsDigestServiceI;
import com.example.springai.service.NewsEmailRenderer;
import com.example.springai.service.NewsSourceI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class NewsDigestService implements NewsDigestServiceI {

    /** 邮件主题里的日期格式。 */
    private static final String SUBJECT_PREFIX = "【知行信咨询助手】每日新闻摘要 · ";

    @Autowired
    private KbNewsMapper newsMapper;

    @Autowired
    private NewsProperties props;

    @Autowired
    private NewsEmailRenderer renderer;

    @Autowired
    private EmailServiceI emailService;

    /** 所有新闻源。加新源只要写个 {@code @Component implements NewsSourceI}，这里不用改。 */
    @Autowired
    private List<NewsSourceI> sources;

    // ==================== 抓取 ====================

    @Override
    public int fetchAndStore() {
        List<KbNews> fetched = new ArrayList<>();

        // 每个源单独 try/catch：一个源挂了不该让整轮抓取颗粒无收。
        // 源内部的契约是"真故障抛异常、正常没内容返回空列表"，这里把前者降级成 WARN。
        for (NewsSourceI source : sources) {
            try {
                List<KbNews> items = source.fetch();
                fetched.addAll(items);
            } catch (Exception e) {
                log.warn("⚠️ 新闻源 {} 抓取失败，跳过本轮: {}", source.sourceType(), e.getMessage());
            }
        }

        if (fetched.isEmpty()) {
            log.warn("本轮没有从任何源抓到新闻（全部失败或全部为空）");
            return 0;
        }

        int beforeScoreFilter = fetched.size();
        fetched.removeIf(this::belowScoreThreshold);
        if (fetched.size() < beforeScoreFilter) {
            log.info("按评分阈值 {} 过滤掉 {} 条", props.getMinScore(), beforeScoreFilter - fetched.size());
        }

        tagKeywords(fetched);

        LocalDateTime now = AppTime.now();
        int inserted = 0;
        for (KbNews news : fetched) {
            news.setFetchedAt(now);
            try {
                // INSERT IGNORE：撞去重键静默跳过。"昨天已经抓过"是每时每刻都在发生的正常情况，
                // 不该用异常来表达（每天几百次异常既吵又慢）
                inserted += newsMapper.insertIgnore(news);
            } catch (DataAccessException e) {
                // 表还没建（DDL 没执行）时不要整轮炸掉，记一次就够
                log.error("❌ 新闻入库失败（kb_news 表是否已建？见 doc/schema.sql 的 2026-09-15 段）: {}",
                        e.getMessage());
                break;
            }
        }
        log.info("📰 本轮抓取 {} 条，新增入库 {} 条（其余为已抓过的）", fetched.size(), inserted);
        return inserted;
    }

    /**
     * 评分过滤。**只过滤有评分的条目** —— RSS 源没有 score 字段，
     * 一律保留，不能因为"没有评分"就当成低分丢掉。
     *
     * @param minScore <= 0 表示不按评分过滤
     */
    private boolean belowScoreThreshold(KbNews news) {
        if (props.getMinScore() <= 0 || news.getScore() == null) {
            return false;
        }
        return news.getScore() < props.getMinScore();
    }

    /** 把命中的关注关键词写进 {@code matchedKeywords}，邮件据此把它们排到最前面。 */
    private void tagKeywords(List<KbNews> items) {
        List<String> keywords = props.getKeywords();
        if (keywords == null || keywords.isEmpty()) {
            return;
        }
        for (KbNews news : items) {
            String haystack = (nz(news.getTitle()) + " " + nz(news.getSummary())).toLowerCase();
            String matched = keywords.stream()
                    .filter(k -> k != null && !k.isBlank())
                    .filter(k -> haystack.contains(k.toLowerCase()))
                    .collect(Collectors.joining(","));
            news.setMatchedKeywords(matched.isEmpty() ? null : matched);
        }
    }

    // ==================== 推送 ====================

    @Override
    public int sendDigest() {
        List<String> recipients = props.getRecipients().stream()
                .filter(r -> r != null && !r.isBlank())
                .collect(Collectors.toList());
        if (recipients.isEmpty()) {
            // 空名单不是错误：可以先让新闻攒着，观察抓取质量再决定发给谁
            log.info("未配置新闻收件人（app.news.recipients），跳过推送");
            return 0;
        }

        List<KbNews> pending;
        try {
            pending = newsMapper.selectPending();
        } catch (DataAccessException e) {
            log.error("❌ 读取待推送新闻失败（kb_news 表是否已建？）: {}", e.getMessage());
            return 0;
        }
        if (pending == null || pending.isEmpty()) {
            log.info("没有新的新闻需要推送");
            return 0;
        }

        List<KbNews> picked = selectForEmail(pending);
        int notShown = pending.size() - picked.size();
        String html = renderer.render(picked, AppTime.now().toLocalDate(), notShown);
        String subject = SUBJECT_PREFIX + AppTime.now().toLocalDate();

        int ok = 0;
        List<String> failed = new ArrayList<>();
        for (String to : recipients) {
            try {
                emailService.sendHtmlEmail(to, subject, html);
                ok++;
            } catch (Exception e) {
                failed.add(to);
                log.error("❌ 新闻推送失败 recipient={}: {}", to, e.getMessage());
            }
        }

        if (ok == 0) {
            // 一封都没发出去 —— **不要标记 pushed_at**，否则这批新闻再也不会重发。
            // 明天那一轮会把它们重新捞出来。
            log.error("❌ 本轮新闻一封都没发出去（{} 个收件人全部失败），已保留待发状态，下轮重试", failed.size());
            return 0;
        }
        if (!failed.isEmpty()) {
            log.warn("⚠️ 有 {} 个收件人发送失败（{}），但已有成功的，本次仍标记为已推送 —— "
                            + "否则成功的那些明天会重复收到同一批。请检查失败地址是否有效",
                    failed.size(), failed);
        }

        // 标记**全部**待推送的，而不只是邮件里列出来的那些。
        // 列表之外的是被 topPerCategory / maxPerEmail 截掉的，邮件里已注明"另有 N 条未列出"。
        // 只标记展示过的话，超出上限的部分会永远留在待发集合里越积越多。
        List<Long> ids = pending.stream().map(KbNews::getId).collect(Collectors.toList());
        newsMapper.markPushed(ids, AppTime.now());

        log.info("📧 新闻推送完成：{} 条 → {} 个收件人（另有 {} 条未在邮件中列出）",
                picked.size(), ok, notShown);
        return picked.size();
    }

    /**
     * 从待推送里挑出邮件要展示的：每类取前 {@code topPerCategory} 条，
     * 再整体按评分倒序取前 {@code maxPerEmail} 条。
     *
     * <p>两级上限是必要的：只限总量的话，某一类（比如"观点与技巧"占了 38/96）
     * 会把别的分类全挤掉，邮件只剩一种内容。
     */
    private List<KbNews> selectForEmail(List<KbNews> pending) {
        // 用 LinkedHashMap 分组以保持稳定顺序（普通 HashMap 的顺序不确定，
        // 会让同一天的两封邮件排版不一样）
        Map<String, List<KbNews>> byCategory = pending.stream()
                .collect(Collectors.groupingBy(
                        n -> n.getCategory() == null ? "" : n.getCategory(),
                        LinkedHashMap::new, Collectors.toList()));

        List<KbNews> picked = new ArrayList<>();
        for (List<KbNews> group : byCategory.values()) {
            picked.addAll(group.stream().limit(Math.max(props.getTopPerCategory(), 1)).toList());
        }

        // 评分高的在前；没有评分的（RSS）排在后面 —— 它们没有可比的分值
        picked.sort(Comparator
                .comparing(KbNews::getScore, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(KbNews::getPublishedAt, Comparator.nullsLast(Comparator.reverseOrder())));

        return picked.stream().limit(Math.max(props.getMaxPerEmail(), 1)).toList();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
