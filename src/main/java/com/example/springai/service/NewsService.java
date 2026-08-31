package com.example.springai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;


@Slf4j
@Service
public class NewsService implements NewsServiceI{

    @Value("${news.api.base-url:https://aihot.virxact.com/api/v1/items}")
    private String apiBaseUrl;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public NewsService() {
        this.webClient = WebClient.builder().build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 获取 AI 热门资讯
     *
     * @param limit  返回的新闻条数，默认 5，最大 20
     * @param window 时间窗口，如 "24h"、"7d"，默认 "24h"
     * @return 格式化后的新闻列表
     */
    @Override
    public String getAINews(int limit, String window) {
        int actualLimit = Math.min(limit, 20);
        String actualWindow = (window != null && !window.isEmpty()) ? window : "24h";

        try {
            log.info("📰 正在从 AI HOT API 获取新闻，limit={}, window={}", actualLimit, actualWindow);

            // 构建请求 URL（默认 mode=selected）
            String url = apiBaseUrl + "?mode=selected&window=" + actualWindow + "&limit=" + actualLimit;

            String response = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (response == null || response.isEmpty()) {
                return "未获取到新闻数据";
            }

            // 解析 JSON 响应（API 返回的是数组）
            JsonNode root = objectMapper.readTree(response);
            if (!root.isArray()) {
                // 如果返回的是对象，尝试取 items 字段
                if (root.has("items") && root.get("items").isArray()) {
                    root = root.get("items");
                } else {
                    return "新闻数据格式异常";
                }
            }

            if (root.size() == 0) {
                return "暂无新闻数据";
            }

            // 构建格式化输出
            StringBuilder result = new StringBuilder("📰 **AI 热门资讯**\n\n");
            for (int i = 0; i < root.size(); i++) {
                JsonNode item = root.get(i);
                String title = item.has("title") ? item.get("title").asText() : "无标题";
                String summary = item.has("summary") ? item.get("summary").asText() : "";
                String source = item.has("source") ? item.get("source").asText() : "未知来源";
                String urlLink = item.has("url") ? item.get("url").asText() : "";

                result.append(i + 1).append(". **").append(title).append("**\n");
                if (!summary.isEmpty()) {
                    result.append("   📝 ").append(summary).append("\n");
                }
                result.append("   📍 来源：").append(source);
                if (!urlLink.isEmpty()) {
                    result.append("  🔗 [阅读原文](").append(urlLink).append(")");
                }
                result.append("\n\n");
            }

            return result.toString();

        } catch (Exception e) {
            log.error("获取新闻失败: {}", e.getMessage(), e);
            return "获取新闻失败: " + e.getMessage();
        }
    }
}