package com.example.springai.controller;

import com.example.springai.common.ErrorCode;
import com.example.springai.exception.BizException;
import com.example.springai.service.impl.AnonymousQuestionLimiter;
import com.example.springai.utils.IpUtils;
import com.example.springai.common.Response;
import com.example.springai.entity.Conversation;
import com.example.springai.entity.KbQuestionLog;
import com.example.springai.entity.SysUser;
import com.example.springai.service.ConversationServiceI;
import com.example.springai.service.QuestionLogServiceI;
import com.example.springai.service.RagServiceI;
import com.example.springai.service.UserServiceI;
import com.example.springai.utils.JwtUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
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
    @Autowired
    private QuestionLogServiceI questionLogService;
    @Autowired
    private AnonymousQuestionLimiter anonymousQuestionLimiter;
    @Autowired
    private IpUtils ipUtils;

    /**
     * 判定当前请求是不是未登录调用。
     *
     * <p>不能只判 {@code auth == null}：Spring Security 的
     * {@code AnonymousAuthenticationFilter} 会给匿名请求塞一个
     * {@code AnonymousAuthenticationToken}，而它的 {@code isAuthenticated()}
     * 返回的是 <b>true</b> —— 只看 isAuthenticated() 会把匿名当成已登录，
     * 配额就永远不会扣。
     */
    private boolean isAnonymous() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null
                || !auth.isAuthenticated()
                || auth instanceof AnonymousAuthenticationToken;
    }

    /**
     * 查询当前的匿名提问配额。只读，不扣次数。
     *
     * <p>路径挂在 {@code /api/rag/chat} 下是为了落在 SecurityConfig 已放行的
     * 前缀里，不用再单独加一条规则。
     */
    @GetMapping("/chat/quota")
    public Response<Map<String, Object>> quota(HttpServletRequest request) {
        boolean anonymous = isAnonymous();
        Map<String, Object> data = new HashMap<>();
        data.put("anonymous", anonymous);
        data.put("enabled", anonymousQuestionLimiter.isEnabled());
        data.put("limit", anonymousQuestionLimiter.getPerIpDailyLimit());
        data.put("remaining", anonymous
                ? anonymousQuestionLimiter.remaining(ipUtils.getClientIp(request))
                : -1);
        return Response.success(data);
    }

    /**
     * 普通 RAG 问答（阻塞式）
     * 根据问题关键词自动选择工具模式或文档检索模式
     */
    @GetMapping("/chat")
    public Response<Map<String, Object>> chat(@RequestParam String question,
                                              HttpServletRequest request) {
        log.info("📨 收到RAG问答请求: {}", question);
        // 匿名调用先扣配额；超限会抛 BizException，由全局处理器转成 200 + errCode 429
        if (isAnonymous()) {
            anonymousQuestionLimiter.checkAndRecord(ipUtils.getClientIp(request));
        }
        try {
            String answer;
            if (question.contains("天气") || question.contains("新闻") || question.contains("热点")) {
                answer = ragService.chatWithTool(question);
            } else {
                answer = ragService.chatWithDocument(question);
            }
            Map<String, Object> data = new HashMap<>();
            data.put("question", question);
            data.put("answer", answer);
            return Response.success(data);
        } catch (Exception e) {
            log.error("❌ RAG问答失败: {}", e.getMessage(), e);
            // 失败也要留痕，否则看板上看不到任何异常，问题会被静默吞掉
            try {
                SysUser user = userServiceI.findByUsernameOrEmail(
                        SecurityContextHolder.getContext().getAuthentication() == null
                                ? "" : SecurityContextHolder.getContext().getAuthentication().getName());
                questionLogService.record(question,
                        user == null ? null : user.getId(),
                        user == null ? null : user.getDepartmentId(),
                        null, KbQuestionLog.HIT_ERROR, 0, null);
            } catch (Throwable t) {
                log.warn("异常埋点写入失败: {}", t.getMessage());
            }
            // 保持与原行为一致的"处理失败但请求本身成功"语义：HTTP 200 + success:false
            return Response.fail(ErrorCode.INTERNAL_ERROR, "处理失败: " + e.getMessage());
        }
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
        // 1. 带了 token 就必须有效；没带 token 则走匿名路径（不再直接拒绝）
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && !authHeader.isBlank()) {
            try {
                validateToken(authHeader);
            } catch (RuntimeException e) {
                // 若校验失败，返回错误信息并结束流。
                // 注意：这里只给"内容"，不要自己拼 data: 前缀和结尾空行 ——
                // Spring 的 SSE 写出器会对 Flux<String> 的每个元素自动包一层，
                // 手工再拼一次会导致线上出现 "data: data: xxx"。
                return Flux.just(e.getMessage(), "[DONE]");
            }
        }

        boolean anonymous = isAnonymous();
        if (anonymous) {
            // 匿名配额超限也只能走 SSE（本方法返回 Flux，包不了 Response 信封），
            // 沿用上面鉴权失败的同一写法
            try {
                anonymousQuestionLimiter.checkAndRecord(ipUtils.getClientIp(request));
            } catch (BizException e) {
                return Flux.just(e.getMessage(), "[DONE]");
            }
        }

        log.info("📨 收到流式RAG问答请求: {}（{}）", question, anonymous ? "匿名" : "已登录");

        // 2. 处理会话ID。匿名用户不做会话持久化（MongoDB 里不留无主会话），
        //    所以这里必须返回 null，且下面所有写会话的地方都要跳过。
        final String finalConversationId = anonymous
                ? null
                : resolveConversationId(conversationId, question, authentication);

        // 3. 准备AI回答的收集器
        StringBuilder aiAnswer = new StringBuilder();

        // 4. 构建流式响应
        //    先发送一个元数据消息（包含 conversationId 与是否匿名），再发送实际的回答流。
        //    同样只给"内容"，SSE 的 data: 前缀与空行由 Spring 负责。
        Flux<String> metaDataFlux = Flux.just(
                "{\"type\":\"meta\",\"conversationId\":"
                        + (finalConversationId == null ? "null" : "\"" + finalConversationId + "\"")
                        + ",\"anonymous\":" + anonymous + "}"
        );

        Flux<String> aiStream = ragService.chatWithDocumentStream(question, finalConversationId)
                .doOnNext(chunk -> aiAnswer.append(chunk))
                .doOnComplete(() -> {
                    if (finalConversationId != null) {
                        conversationService.addMessage(finalConversationId, "assistant", aiAnswer.toString());
                        log.info("✅ AI回答已保存，会话ID: {}", finalConversationId);
                    }
                })
                .doOnError(e -> log.error("流式问答失败", e));

        // 合并两个流：先发送 meta，再发送 AI 流
        return Flux.concat(metaDataFlux, aiStream);
    }

    /** 取出或新建会话，并把用户提问写入会话。仅已登录用户调用。 */
    private String resolveConversationId(String conversationId, String question,
                                         Authentication authentication) {
        if (conversationId != null && !conversationId.isEmpty()) {
            conversationService.addMessage(conversationId, "user", question);
            log.info("🔁 使用已有会话，ID: {}", conversationId);
            return conversationId;
        }
        SysUser user = userServiceI.findByUsernameOrEmail(authentication.getName());
        Conversation conv = conversationService.createConversation(user.getId(), question);
        conversationService.addMessage(conv.getId(), "user", question);
        log.info("✅ 创建新会话，ID: {}", conv.getId());
        return conv.getId();
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