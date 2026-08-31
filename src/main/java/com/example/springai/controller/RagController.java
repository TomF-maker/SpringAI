package com.example.springai.controller;

import com.example.springai.service.RagServiceI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/rag")
public class RagController {

    @Autowired
    private RagServiceI ragService;

    /**
     * 普通 RAG 问答（阻塞式）
     * 根据问题关键词自动选择工具模式或文档检索模式
     *
     * @param question 用户问题
     * @return JSON 格式的回答
     */
    @GetMapping("/chat")
    public Map<String, Object> chat(@RequestParam String question) {
        log.info("📨 收到RAG问答请求: {}", question);
        Map<String, Object> response = new HashMap<>();
        try {
            String answer;
            // 如果问题包含天气或新闻关键词，使用工具模式
            if (question.contains("天气") || question.contains("新闻") || question.contains("热点")) {
                answer = ragService.chatWithTool(question);
            } else {
                answer = ragService.chatWithDocument(question);
            }
            response.put("success", true);
            response.put("question", question);
            response.put("answer", answer);
        } catch (Exception e) {
            log.error("❌ RAG问答失败: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("question", question);
            response.put("answer", "处理失败: " + e.getMessage());
        }
        return response;
    }

    /**
     * 流式 RAG 问答（支持流式输出）
     *
     * @param question 用户问题
     * @return Server-Sent Events 流
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestParam String question) {
        log.info("📨 收到流式RAG问答请求: {}", question);
        return ragService.chatWithDocumentStream(question);
    }
}