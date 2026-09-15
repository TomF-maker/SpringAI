package com.example.springai.controller;

import com.example.springai.common.ErrorCode;
import com.example.springai.exception.BizException;
import com.example.springai.dto.MembershipStatusDTO;
import com.example.springai.service.MembershipServiceI;
import com.example.springai.service.impl.AnonymousQuestionLimiter;
import com.example.springai.service.impl.ChatAccessGuard;
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
    private ChatAccessGuard chatAccessGuard;
    /** /chat/quota 里要读匿名额度的配置与剩余值，所以这里仍然直接用。 */
    @Autowired
    private AnonymousQuestionLimiter anonymousQuestionLimiter;
    @Autowired
    private MembershipServiceI membershipService;
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
    /**
     * 取当前登录用户；未登录或用户已被删除时返回 null。
     *
     * <p>调用方必须判 null —— 原来流式接口是直接 {@code .getId()}，
     * 用户被删但 token 没过期时就 NPE，而且是在 {@code return Flux} 之前抛，
     * 结果变成 JSON 错误体污染 SSE。
     */
    private SysUser currentUserOrNull(boolean anonymous) {
        if (anonymous) {
            return null;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return null;
        }
        return userServiceI.findByUsernameOrEmail(auth.getName());
    }

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
        SysUser currentUser = currentUserOrNull(anonymous);

        Map<String, Object> data = new HashMap<>();
        data.put("anonymous", anonymous);

        if (anonymous) {
            data.put("enabled", anonymousQuestionLimiter.isEnabled());
            data.put("limit", anonymousQuestionLimiter.getPerIpDailyLimit());
            data.put("remaining", anonymousQuestionLimiter.remaining(ipUtils.getClientIp(request)));
            data.put("member", false);
            return Response.success(data);
        }

        // 已登录：返回会员状态与（非会员的）免费额度，前端直接拿这些渲染横幅
        MembershipStatusDTO status = membershipService.getStatus(
                currentUser == null ? null : currentUser.getId());
        data.put("member", status.isActive());
        if (status.isActive()) {
            data.put("enabled", false);      // 会员不限次，前端据此不显示"剩余 N 条"
            data.put("limit", null);
            data.put("remaining", null);
        } else {
            data.put("enabled", true);
            data.put("limit", status.getFreeDailyLimit());
            data.put("remaining", status.getFreeRemaining());
        }
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

        // 准入判定必须在 try **之外**：放进去会被下面的 catch (Exception) 吞成
        // "处理失败"，还会往 kb_question_log 写一条假的 HIT_ERROR 埋点。
        // 超限时抛 BizException，由全局处理器转成 200 + errCode 429。
        boolean anonymous = isAnonymous();
        SysUser currentUser = currentUserOrNull(anonymous);
        // 完整 IP 在这里取一次并捕获成局部变量：准入判定和提问埋点都要用，
        // 而 service 层读不到 request（归属地回填更是发生在工作线程上）。
        final String clientIp = ipUtils.getClientIp(request);
        chatAccessGuard.checkAndRecord(anonymous, currentUser, clientIp);

        try {
            RagServiceI.Answer result;
            if (question.contains("天气") || question.contains("新闻") || question.contains("热点")) {
                result = ragService.chatWithTool(question, clientIp);
            } else {
                result = ragService.chatWithDocument(question, clientIp);
            }
            Map<String, Object> data = new HashMap<>();
            data.put("question", question);
            data.put("answer", result.getAnswer());
            // 前端提交答案评价时要靠它关联到这次提问
            data.put("questionLogId", result.getQuestionLogId());
            return Response.success(data);
        } catch (Exception e) {
            log.error("❌ RAG问答失败: {}", e.getMessage(), e);
            // 失败也要留痕，否则看板上看不到任何异常，问题会被静默吞掉。
            // 复用上面已经查到的 currentUser —— 失败路径反而比原来少查一次库。
            try {
                questionLogService.record(question,
                        currentUser == null ? null : currentUser.getId(),
                        currentUser == null ? null : currentUser.getDepartmentId(),
                        null, KbQuestionLog.HIT_ERROR, 0, null, clientIp);
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
        // 已登录的先取出用户：下面的完成回调跑在 reactor 线程上，
        // 那时 SecurityContextHolder 已经是空的，必须提前捕获。
        // 顺带修掉一个既有隐患 —— 原来这里直接 .getId()，用户被删但 token 没过期时
        // 会 NPE，而且是在 return Flux 之前抛，结果变成 JSON 错误体污染 SSE。
        final SysUser currentUser = currentUserOrNull(anonymous);
        if (!anonymous && currentUser == null) {
            return Flux.just("用户不存在，请重新登录", "[DONE]");
        }

        // 完整 IP 取一次复用：准入判定和下面的提问埋点都要用，
        // 而 service 层读不到 request（归属地回填发生在工作线程上）。
        final String clientIp = ipUtils.getClientIp(request);

        // 准入判定：匿名按 IP、会员放行、非会员按每日免费额度。超限也只能走 SSE
        // （本方法返回 Flux，包不了 Response 信封），沿用上面鉴权失败的同一写法。
        try {
            chatAccessGuard.checkAndRecord(anonymous, currentUser, clientIp);
        } catch (BizException e) {
            return Flux.just(e.getMessage(), "[DONE]");
        }

        log.info("📨 收到流式RAG问答请求: {}（{}）", question, anonymous ? "匿名" : "已登录");

        // 2. 处理会话ID。匿名用户不做会话持久化（MongoDB 里不留无主会话），所以直接置空。
        final Long currentUserId = currentUser == null ? null : currentUser.getId();

        final String finalConversationId;
        if (anonymous) {
            finalConversationId = null;
        } else {
            try {
                finalConversationId = resolveConversationId(conversationId, question, currentUserId);
            } catch (BizException e) {
                // 会话不存在或不属于该用户。本方法返回 Flux，不能把异常抛出去 ——
                // 那样客户端在流式响应里会收到一个 JSON 错误体，解析直接乱掉。
                // 沿用本项目既有的写法：把消息当成一帧内容发出去。
                log.warn("会话校验未通过: {}", e.getMessage());
                return Flux.just(e.getMessage(), "[DONE]");
            }
        }

        // 3. 先把流和埋点 id 一起拿到，**再**拼 meta 帧 ——
        //    顺序反过来 meta 帧里就拿不到 questionLogId 了（它是在 service 里落库产生的）。
        RagServiceI.AnswerStream answerStream =
                ragService.chatWithDocumentStream(question, finalConversationId, clientIp);

        // 4. 准备AI回答的收集器
        StringBuilder aiAnswer = new StringBuilder();

        // 5. 构建流式响应
        //    先发送一个元数据消息（包含 conversationId、是否匿名、埋点 id），再发送回答流。
        //    同样只给"内容"，SSE 的 data: 前缀与空行由 Spring 负责。
        Flux<String> metaDataFlux = Flux.just(
                "{\"type\":\"meta\",\"conversationId\":"
                        + (finalConversationId == null ? "null" : "\"" + finalConversationId + "\"")
                        + ",\"anonymous\":" + anonymous
                        + ",\"questionLogId\":" + answerStream.getQuestionLogId() + "}"
        );

        Flux<String> aiStream = answerStream.getContent()
                .doOnNext(chunk -> aiAnswer.append(chunk))
                .doOnComplete(() -> {
                    if (finalConversationId != null) {
                        conversationService.addMessage(finalConversationId, currentUserId,
                                "assistant", aiAnswer.toString());
                        log.info("✅ AI回答已保存，会话ID: {}", finalConversationId);
                    }
                })
                .doOnError(e -> log.error("流式问答失败", e));

        // 合并两个流：先发送 meta，再发送 AI 流
        return Flux.concat(metaDataFlux, aiStream);
    }

    /**
     * 取出或新建会话，并把用户提问写入会话。仅已登录用户调用。
     *
     * <p>传入已有 conversationId 时，归属校验在 service 层做 —— 不属于该用户会抛
     * {@link BizException}，由调用方转成 SSE 错误帧。
     */
    private String resolveConversationId(String conversationId, String question, Long userId) {
        if (conversationId != null && !conversationId.isEmpty()) {
            conversationService.addMessage(conversationId, userId, "user", question);
            log.info("🔁 使用已有会话，ID: {}", conversationId);
            return conversationId;
        }
        Conversation conv = conversationService.createConversation(userId, question);
        conversationService.addMessage(conv.getId(), userId, "user", question);
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