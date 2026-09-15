package com.example.springai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 聊天里的即时新闻查询（"最近 AI 有什么新闻"这类问题）。
 *
 * <p>和每日邮件摘要（{@code NewsDigestService}）是两条路：那边是定时的、入库去重的、
 * 面向邮件的；这边是用户临时问的、直接格式化成文本喂给模型的。
 *
 * <p><b>这个类此前有三个问题，都是"不够多、不够准"的直接原因：</b>
 * <ol>
 *   <li>{@code limit} 被写死成 {@code Math.min(limit, 20)} —— 而接口上限是 100；</li>
 *   <li>从不读接口返回的 {@code score}（0-100 策展评分），照单全收 —— 所以"不够准"；</li>
 *   <li>{@code .block()} 没带超时，接口一挂就把调用线程永久挂死（同 RestTemplateConfig 的坑）。</li>
 * </ol>
 */
@Slf4j
@Service
public class NewsService implements NewsServiceI {

    /**
     * 聊天路径的条数上限。
     *
     * <p>刻意**不用接口的 100 上限**：这份文本会整个塞进提示词，而线上跑的是
     * qwen2.5:1.5b —— 一个小模型。喂 100 条（上万字）进去，答案质量会明显下降，
     * 用户要的是"够用的几条好新闻"而不是"一大堆"。要真正看量，走每日邮件摘要。
     */
    private static final int MAX_LIMIT = 30;

    /** 接口只认 24h / 7d。 */
    private static final String DEFAULT_WINDOW = "24h";

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Value("${app.news.aihot.base-url:https://aihot.virxact.com/api/v1/items}")
    private String apiBaseUrl;

    private final WebClient webClient = WebClient.builder().build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 获取 AI 热门资讯。
     *
     * @param limit    返回条数，默认 5，上限 {@value #MAX_LIMIT}
     * @param window   时间窗口，"24h" 或 "7d"，默认 24h
     * @param category 分类过滤：ai-models / ai-products / industry / paper / tip；null 表示不限
     * @return 格式化后的新闻列表
     */
    @Override
    public String getAINews(int limit, String window, String category) {
        int actualLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        String actualWindow = (window == null || window.isBlank()) ? DEFAULT_WINDOW : window.trim();

        try {
            URI uri = UriComponentsBuilder.fromUriString(apiBaseUrl)
                    .queryParam("mode", "selected")
                    .queryParam("window", actualWindow)
                    .queryParam("limit", actualLimit)
                    // 用 UriComponentsBuilder 而不是手拼字符串：分类和窗口都来自
                    // 模型输出的 JSON，手拼等于把模型当可信输入
                    .queryParamIfPresent("category",
                            (category == null || category.isBlank())
                                    ? java.util.Optional.empty()
                                    : java.util.Optional.of(category.trim()))
                    .build().encode().toUri();

            log.info("📰 正在获取 AI 新闻: limit={}, window={}, category={}",
                    actualLimit, actualWindow, category);

            String response = webClient.get()
                    .uri(uri)
                    .retrieve()
                    .bodyToMono(String.class)
                    // 必须带超时：裸的 block() 会让接口一挂就永久挂死调用线程
                    .block(TIMEOUT);

            if (response == null || response.isBlank()) {
                return "未获取到新闻数据";
            }

            JsonNode root = objectMapper.readTree(response);
            // 参数非法时接口返回 {"query":null,"items":[]} —— 要说清是参数问题，
            // 别让用户以为"今天真的没新闻"
            if (root.path("query").isNull()) {
                return "新闻查询参数不被接受（window 只支持 24h / 7d，category 只支持 "
                        + "ai-models / ai-products / industry / paper / tip）";
            }
            if (!root.path("items").isArray()) {
                return "新闻数据格式异常";
            }

            List<JsonNode> items = new ArrayList<>();
            root.path("items").forEach(items::add);
            if (items.isEmpty()) {
                return "暂无新闻数据";
            }

            // 按策展评分倒序 —— 这是"不够准"的修法：接口给的顺序是按时间排的，
            // 不排序的话低分条目会跟高分条目混在一起，模型也没法判断哪条更值得说
            items.sort(Comparator.comparingInt(
                    n -> n.path("score").isNumber() ? -n.path("score").asInt() : Integer.MIN_VALUE));

            return format(items);

        } catch (Exception e) {
            log.error("获取新闻失败: {}", e.getMessage(), e);
            return "获取新闻失败: " + e.getMessage();
        }
    }

    private String format(List<JsonNode> items) {
        StringBuilder result = new StringBuilder("📰 **AI 热门资讯**\n\n");
        for (int i = 0; i < items.size(); i++) {
            JsonNode item = items.get(i);
            result.append(i + 1).append(". **")
                  .append(text(item, "title", "无标题")).append("**\n");

            String summary = text(item, "summary", null);
            if (summary != null) {
                result.append("   📝 ").append(summary).append("\n");
            }

            result.append("   📍 来源：").append(text(item, "source", "未知来源"));
            // 把评分和分类也带上：模型据此能挑重点讲，而不是把 5 条平铺一遍
            if (item.path("score").isNumber()) {
                result.append("  ⭐ ").append(item.path("score").asInt());
            }
            String category = text(item, "category", null);
            if (category != null) {
                result.append("  🏷 ").append(category);
            }

            String url = item.path("links").path("original").asText(null);
            if (url != null && !url.isBlank()) {
                result.append("  🔗 [阅读原文](").append(url).append(")");
            }
            result.append("\n\n");
        }
        return result.toString();
    }

    /** source 是嵌套对象（{@code {"name": "..."}}），其余是字符串。 */
    private static String text(JsonNode item, String field, String fallback) {
        JsonNode node = item.path(field);
        String value = "source".equals(field) ? node.path("name").asText(null) : node.asText(null);
        return value == null || value.isBlank() ? fallback : value;
    }
}
