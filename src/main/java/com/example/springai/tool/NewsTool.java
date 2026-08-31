package com.example.springai.tool;

import com.example.springai.service.NewsServiceI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class NewsTool {

    @Autowired
    private NewsServiceI newsServiceI;

    @Tool(name = "getAINews", description = "获取 AI 领域的最近热门资讯和新闻，可按时间窗口（如 24 小时、7 天）和数量筛选")
    public String getAINews(
            @ToolParam(description = "返回的新闻条数，默认 5，最多 20") Integer limit,
            @ToolParam(description = "时间窗口，如 '24h' 表示最近 24 小时，'7d' 表示最近 7 天，默认 '24h'") String window) {
        int actualLimit = limit != null ? limit : 5;
        String actualWindow = (window != null && !window.isEmpty()) ? window : "24h";
        log.info("🔧 NewsTool 被调用：limit={}, window={}", limit, window);  // 添加这行
        return newsServiceI.getAINews(actualLimit, actualWindow);
    }
}