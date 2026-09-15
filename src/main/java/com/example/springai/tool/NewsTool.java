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

    @Tool(name = "getAINews", description = "获取 AI 领域的最近热门资讯，可按时间窗口、数量和分类筛选。"
            + "返回结果已按策展评分倒序，越靠前越值得关注")
    public String getAINews(
            @ToolParam(description = "返回的新闻条数，默认 5") Integer limit,
            @ToolParam(description = "时间窗口：'24h' 表示最近 24 小时，'7d' 表示最近 7 天。默认 '24h'")
            String window,
            @ToolParam(required = false, description = "只要某一类时填写："
                    + "ai-models=模型发布/评测，ai-products=产品与功能，industry=行业与公司动态，"
                    + "paper=论文研究，tip=观点与技巧。不填表示不限")
            String category) {
        int actualLimit = limit != null ? limit : 5;
        String actualWindow = (window == null || window.isEmpty()) ? "24h" : window;
        log.info("🔧 NewsTool 被调用：limit={}, window={}, category={}", limit, window, category);
        return newsServiceI.getAINews(actualLimit, actualWindow, category);
    }
}
