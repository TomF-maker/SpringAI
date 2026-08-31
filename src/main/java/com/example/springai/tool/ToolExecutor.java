package com.example.springai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Iterator;

@Slf4j
@Component
public class ToolExecutor {

    @Autowired
    private WeatherTool weatherTool;

    @Autowired
    private NewsTool newsTool;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 判断文本是否为工具调用 JSON（支持多种格式）
     */
    public boolean isToolCall(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String trimmed = text.trim();
        // 必须是 JSON 对象
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return false;

        try {
            JsonNode root = objectMapper.readTree(trimmed);
            // 格式1: {"name": "xxx", "arguments": {...}}
            if (root.has("name") && root.has("arguments")) {
                return true;
            }
            // 格式2: {"getWeather": {...}} 或 {"getAINews": {...}} 等
            Iterator<String> fieldNames = root.fieldNames();
            while (fieldNames.hasNext()) {
                String field = fieldNames.next();
                if (isKnownTool(field)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 执行工具调用（兼容多种格式）
     */
    public String execute(String toolCallJson) {
        try {
            JsonNode root = objectMapper.readTree(toolCallJson);
            String toolName = null;
            JsonNode args = null;

            // 格式1: {"name": "xxx", "arguments": {...}}
            if (root.has("name") && root.has("arguments")) {
                toolName = root.get("name").asText();
                args = root.get("arguments");
            }
            // 格式2: {"getWeather": {...}} 或 {"getAINews": {...}}
            else {
                Iterator<String> fieldNames = root.fieldNames();
                while (fieldNames.hasNext()) {
                    String field = fieldNames.next();
                    if (isKnownTool(field)) {
                        toolName = field;
                        args = root.get(field);
                        break;
                    }
                }
            }

            if (toolName == null) {
                return "未识别的工具调用格式: " + toolCallJson;
            }

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
            log.error("执行工具失败: {}", e.getMessage(), e);
            return "执行工具失败: " + e.getMessage();
        }
    }

    /**
     * 判断是否为已知工具名称
     */
    private boolean isKnownTool(String name) {
        return "getWeather".equals(name) || "getAINews".equals(name);
    }
}