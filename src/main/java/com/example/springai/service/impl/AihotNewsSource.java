package com.example.springai.service.impl;

import com.example.springai.common.AppTime;
import com.example.springai.config.NewsProperties;
import com.example.springai.entity.KbNews;
import com.example.springai.service.NewsSourceI;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI HOT 聚合接口。
 *
 * <p><b>这个源此前被浪费了一大半能力</b>，旧实现（{@code NewsService}）只用了
 * {@code mode=selected&window=24h&limit=20}，而接口实际支持：
 * <ul>
 *   <li>{@code limit} 上限是 <b>100</b>（101 直接拒绝，返回空）—— 旧代码砍到 20，白扔 80%；</li>
 *   <li>条目自带 {@code score}（0-100 策展评分）和 {@code reason}（入选理由）——
 *       旧代码完全没读，所以"不够准"；</li>
 *   <li>{@code category} 有 5 个取值，可服务端过滤；</li>
 *   <li>{@code q} 支持关键词搜索。</li>
 * </ul>
 *
 * <p>这里同时拉 {@code selected} 和 {@code all} 两轮来扩大覆盖：
 * {@code selected} 是精选（有评分和理由），{@code all} 补上没被精选但确实存在的条目。
 * 两边可能重叠 —— 同一 id 以 {@code selected} 版本为准（它带的元信息更多），
 * 重复的靠 {@code dedupKey} 在入库时兜底。
 */
@Slf4j
@Component
public class AihotNewsSource implements NewsSourceI {

    /** 接口硬上限。传 101 会返回 0 条而不是报错，所以必须夹紧。 */
    private static final int MAX_LIMIT = 100;

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Autowired
    private NewsProperties props;

    private final WebClient webClient = WebClient.builder().build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String sourceType() {
        return KbNews.SOURCE_API;
    }

    @Override
    public List<KbNews> fetch() throws Exception {
        // LinkedHashMap 保序 + 去重：selected 先放，all 里重复的 id 不覆盖它
        Map<String, KbNews> merged = new LinkedHashMap<>();
        for (JsonNode item : request("selected")) {
            put(merged, item);
        }
        for (JsonNode item : request("all")) {
            put(merged, item);
        }
        log.info("📰 AI HOT 抓取完成：{} 条", merged.size());
        return new ArrayList<>(merged.values());
    }

    private void put(Map<String, KbNews> merged, JsonNode item) {
        KbNews news = toNews(item);
        if (news != null) {
            // putIfAbsent 而不是 put：selected 版本带 score/reason，别被 all 的空版本盖掉
            merged.putIfAbsent(news.getDedupKey(), news);
        }
    }

    private JsonNode request(String mode) throws Exception {
        URI uri = UriComponentsBuilder.fromUriString(props.getAihot().getBaseUrl())
                .queryParam("mode", mode)
                .queryParam("window", props.getAihot().getWindow())
                .queryParam("limit", MAX_LIMIT)
                .build()
                .encode()
                .toUri();

        String body = webClient.get()
                .uri(uri)
                .retrieve()
                .bodyToMono(String.class)
                // 必须带超时：旧实现是裸的 block()，源一挂定时任务就永久挂死，
                // 而且不会有任何告警（同 RestTemplateConfig 那个共享 Bean 的坑）。
                .block(TIMEOUT);

        if (body == null || body.isBlank()) {
            throw new IllegalStateException("AI HOT 返回空响应 mode=" + mode);
        }
        JsonNode root = objectMapper.readTree(body);
        // 参数非法时接口返回 {"query":null,"items":[]} —— 不当成"今天没新闻"，
        // 否则参数写错了会静默变成"每天都没有新闻"
        if (root.path("query").isNull()) {
            throw new IllegalStateException("AI HOT 拒绝了本次查询（query 为 null），"
                    + "检查 window 是否只用了 24h/7d、limit 是否 <= " + MAX_LIMIT);
        }
        JsonNode items = root.path("items");
        return items.isArray() ? items : objectMapper.createArrayNode();
    }

    private KbNews toNews(JsonNode item) {
        String id = item.path("id").asText(null);
        String title = item.path("title").asText(null);
        if (id == null || title == null) {
            return null;   // 没有 id 就没法去重，宁可不收
        }

        KbNews news = new KbNews();
        news.setDedupKey(id);
        news.setSourceType(KbNews.SOURCE_API);
        news.setSourceName(item.path("source").path("name").asText("未知来源"));
        news.setCategory(textOrNull(item, "category"));
        news.setTitle(title);
        news.setSummary(textOrNull(item, "summary"));
        news.setReason(textOrNull(item, "reason"));

        // 优先原文链接；拿不到就退回聚合页。别把 null 塞进去 —— 邮件里会出现空链接
        String url = textOrNull(item.path("links"), "original");
        if (url == null) {
            url = textOrNull(item.path("links"), "aihot");
        }
        news.setUrl(url);

        // 显式判空再赋：写成 `isInt() ? asInt() : null` 会走到 int/Integer 的装箱歧义上
        JsonNode score = item.path("score");
        news.setScore(score.isNumber() ? score.asInt() : null);
        news.setPublishedAt(parseInstant(item.path("publishedAt").asText(null)));
        return news;
    }

    /**
     * 解析 ISO-8601 瞬时（如 {@code 2026-09-15T03:14:09.000Z}）并转成北京时间。
     *
     * <p><b>不能直接 {@code LocalDateTime.parse}</b>：字符串带 Z，那是 UTC 的瞬时，
     * 直接丢掉偏移量会让每条新闻的时间差 8 小时 —— 而"过去 24 小时"的筛选、
     * 邮件里的排序都建立在这个字段上。
     */
    private static LocalDateTime parseInstant(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(iso).atZoneSameInstant(AppTime.ZONE).toLocalDateTime();
        } catch (Exception e) {
            log.debug("发布时间解析失败，按未知处理: {}", iso);
            return null;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return s == null || s.isBlank() ? null : s.trim();
    }
}
