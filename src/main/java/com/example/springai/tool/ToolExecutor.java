package com.example.springai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ToolExecutor {

    @Autowired
    private WeatherTool weatherTool;

    @Autowired
    private NewsTool newsTool;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 判断文本是否为工具调用 JSON
     */
    public boolean isToolCall(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String trimmed = text.trim();
        return trimmed.startsWith("{") && trimmed.contains("\"name\"") && trimmed.contains("\"arguments\"");
    }

    /**
     * 执行工具调用
     *
     * @param toolCallJson 例如 {"name":"getWeather","arguments":{"city":"深圳"}}
     * @return 工具执行结果字符串
     */
    public String execute(String toolCallJson) {
        try {
            JsonNode root = objectMapper.readTree(toolCallJson);
            String toolName = root.path("name").asText();
            JsonNode args = root.path("arguments");

            switch (toolName) {
                case "getWeather":
                    String city = args.has("city") ? args.get("city").asText() : "";
                    return weatherTool.getWeather(city);
                case "getAINews":
                    int limit = args.has("limit") ? args.get("limit").asInt() : 5;
                    String window = args.has("window") ? args.get("window").asText() : "24h";
                    return newsTool.getAINews(limit, window);
                default:
                    return "未知工具: " + toolName;
            }
        } catch (Exception e) {
            return "执行工具失败: " + e.getMessage();
        }
    }
}