package com.example.springai.controller;

import com.example.springai.entity.Conversation;
import com.example.springai.entity.SysUser;
import com.example.springai.service.ConversationServiceI;
import com.example.springai.service.RagServiceI;
import com.example.springai.service.UserServiceI;
import com.example.springai.utils.JwtUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
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
    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private UserDetailsService userDetailsService;
    @Autowired
    private ConversationServiceI conversationService;
    @Autowired
    private UserServiceI userServiceI;

    /**
     * 普通 RAG 问答（阻塞式）
     * 根据问题关键词自动选择工具模式或文档检索模式
     */
    @GetMapping("/chat")
    public Map<String, Object> chat(@RequestParam String question) {
        log.info("📨 收到RAG问答请求: {}", question);
        Map<String, Object> response = new HashMap<>();
        try {
            String answer;
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
     * 新增功能：
     *   - 当 conversationId 为空时自动创建新会话，并在流式响应的第一条消息中返回会话ID
     *   - 前端可从中提取 conversationId 用于后续追加消息
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestParam String question,
                                   @RequestParam(required = false) String conversationId,
                                   Authentication authentication,
                                   HttpServletRequest request) {
        // 1. Token 校验（沿用原有逻辑）
        String authHeader = request.getHeader("Authorization");
        try {
            validateToken(authHeader);
        } catch (RuntimeException e) {
            // 若校验失败，返回错误信息并结束流
            return Flux.just("data: " + e.getMessage() + "\n\n", "data: [DONE]\n\n");
        }

        log.info("📨 收到流式RAG问答请求: {}", question);

        // 2. 处理会话ID
        String finalConversationId;
        if (conversationId == null || conversationId.isEmpty()) {
            // 创建新会话
            SysUser user = userServiceI.findByUsernameOrEmail(authentication.getName());
            Conversation conv = conversationService.createConversation(user.getId(), question);
            finalConversationId = conv.getId();
            log.info("✅ 创建新会话，ID: {}", finalConversationId);
        } else {
            finalConversationId = conversationId;
            log.info("🔁 使用已有会话，ID: {}", finalConversationId);
        }

        // 3. 保存用户消息
        conversationService.addMessage(finalConversationId, "user", question);

        // 4. 准备AI回答的收集器
        StringBuilder aiAnswer = new StringBuilder();

        // 5. 构建流式响应
        //    先发送一个元数据消息（包含 conversationId），再发送实际的回答流
        Flux<String> metaDataFlux = Flux.just(
                "data: {\"type\":\"meta\",\"conversationId\":\"" + finalConversationId + "\"}\n\n"
        );

        Flux<String> aiStream = ragService.chatWithDocumentStream(question)
                .doOnNext(chunk -> aiAnswer.append(chunk))
                .doOnComplete(() -> {
                    // 保存AI回答到会话
                    conversationService.addMessage(finalConversationId, "assistant", aiAnswer.toString());
                    log.info("✅ AI回答已保存，会话ID: {}", finalConversationId);
                })
                .doOnError(e -> log.error("流式问答失败", e));

        // 合并两个流：先发送 meta，再发送 AI 流
        return Flux.concat(metaDataFlux, aiStream);
    }

    /**
     * 校验 Token 并设置认证上下文
     */
    private UserDetails validateToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new RuntimeException("未提供Token");
        }
        String jwtToken = authHeader.substring(7);
        String username;
        try {
            username = jwtUtils.getUsernameFromToken(jwtToken);
        } catch (Exception e) {
            throw new RuntimeException("Token无效");
        }
        UserDetails userDetails = userDetailsService.loadUserByUsername(username);
        if (!jwtUtils.validateToken(jwtToken, userDetails.getUsername())) {
            throw new RuntimeException("Token已过期或无效");
        }
        // 设置认证上下文（供后续使用）
        UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authToken);
        return userDetails;
    }
}