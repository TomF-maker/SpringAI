package com.example.springai.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
public class ChatController {

    private final ChatClient chatClient;

    public ChatController(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @GetMapping("/ai/chat")
    public String chat(@RequestParam(defaultValue = "你好，请介绍一下你自己") String message) {
        return chatClient.prompt()
                .user(message)
                .call()
                .content();
    }

    @GetMapping("/ai/assistant")
    public String assistant(@RequestParam(defaultValue = "采购") String topic) {
        return chatClient.prompt()
                .system("你是一位资深的采购专家顾问，擅长供应商管理和成本控制。请用专业、简洁的方式回答问题。")
                .user("请给我关于" + topic + "方面的3条专业建议")
                .call()
                .content();
    }

    @GetMapping("/ai/analyze")
    public Map<String, Object> analyze(@RequestParam String text) {
        String result = chatClient.prompt()
                .user("请分析以下采购需求，返回JSON格式：{\"category\": \"品类\", \"urgency\": \"紧急程度\", \"budget\": \"预算建议\"}\n" + text)
                .call()
                .content();

        // 简单解析成Map
        Map<String, Object> response = new HashMap<>();
        response.put("analysis", result);
        response.put("timestamp", LocalDateTime.now());
        return response;
    }
}