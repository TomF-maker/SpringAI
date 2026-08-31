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
     * RAG 智能问答接口
     *
     * 测试方式：浏览器或curl
     * curl "http://localhost:8080/api/rag/chat?question=你的问题"
     *
     * @param question 用户问题
     * @return 基于文档的回答
     */
    @GetMapping("/chat")
    public Map<String, Object> chat(@RequestParam String question) {
        log.info("📨 收到RAG问答请求: {}", question);

        Map<String, Object> response = new HashMap<>();

        try {
            String answer = ragService.chatWithDocument(question);
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
     * 流式 RAG 问答 - 标准 SSE 格式
     *
     * 测试方式：浏览器访问
     * http://localhost:8080/api/rag/chat/stream?question=这份文档的主要内容是什么？
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestParam String question) {
        log.info("📨 收到流式RAG问答请求: {}", question);

        // 调用 Service 获取流式数据，并包装成 SSE 格式
        return ragService.chatWithDocumentStream(question)
                .map(chunk -> "data: " + chunk + "\n\n")   // 包装成 SSE 格式
                .concatWith(Flux.just("data: [DONE]\n\n")); // 结束标记
    }
}