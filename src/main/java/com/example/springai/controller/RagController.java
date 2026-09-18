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
import com.example.springai.service.RagConcurrencyLimiterI;
import com.example.springai.service.RagServiceI;
import com.example.springai.service.UserServiceI;
import com.example.springai.utils.JwtUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    /** 并发闸门。两条提问路径都要过它 —— 服务器只有 2 核，不设限会同时把大家拖慢。 */
    @Autowired
    private RagConcurrencyLimiterI concurrencyLimiter;

    /** 序列化流式 meta 帧（含 sources 来源标注）。new 一个够用，无需走容器。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

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
                                              @RequestParam(required = false) String conversationId,
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

        final Long currentUserId = currentUser == null ? null : currentUser.getId();
        // 客户公司归属，用于会话隔离标记（P0：会话列表仍按 userId 隔离，clientId 为 P1 铺路）
        final Long currentCompanyId = currentUser == null ? null : currentUser.getCompanyId();

        // 并发名额。和流式路径同一个闸门、同一套顺序（**先名额、后配额**）——
        // 反过来的话服务器忙时被拒的请求会白扣一次配额。
        final RagConcurrencyLimiterI.Permit permit;
        try {
            permit = concurrencyLimiter.acquire(currentUserId, clientIp);
        } catch (BizException e) {
            return Response.fail(ErrorCode.INTERNAL_ERROR, e.getMessage());
        }

        // 配额判定。**必须让它自己的 BizException 逃出去** —— 原来的写法就是让它
        // 冒到全局处理器转成 200 + errCode 429，套进下面的 catch (Exception) 会变成
        // "处理失败: ..." + INTERNAL_ERROR，把"今日额度用完"伪装成服务端故障。
        try {
            chatAccessGuard.checkAndRecord(anonymous, currentUser, clientIp);
        } catch (BizException e) {
            permit.close();
            throw e;
        }

        // 声明在 try 之外：catch 里的异常埋点要用它 —— 带着会话 id 的失败提问
        // 才能被归到对应会话下，否则看板上那一条是孤立的
        String finalConversationId = null;
        try {
            // 会话归属。
            //
            // **这条路径以前完全不碰会话**：前端一直在传 conversationId，
            // 但方法签名里根本没有这个参数，于是被静默忽略。后果是凡走这条路的提问
            // 都不进历史记录 —— 而 shouldUseTool 命中「天气/新闻/热点/气温/预报/AI/人工智能」，
            // 其中 **"AI" 是个极易命中的子串**（"最近AI有什么新闻"甚至"什么是AI"）。
            // 用户连问两问、第一问含 "AI"，历史里就只剩第二问，看起来像丢了。
            //
            // 现在和流式路径共用同一个 resolveConversationId，行为对齐。
            if (!anonymous) {
                finalConversationId = resolveConversationId(conversationId, question, currentUserId, currentCompanyId);
            }

            RagServiceI.Answer result;
            if (question.contains("天气") || question.contains("新闻") || question.contains("热点")) {
                result = ragService.chatWithTool(question, finalConversationId, clientIp);
            } else {
                result = ragService.chatWithDocument(question, finalConversationId, clientIp);
            }

            // 把回答写进会话。流式路径是在 doOnComplete 里做的（因为答案要边流边攒），
            // 这里答案已经完整拿到，同步写即可。sources 一并持久化，历史记录重进仍能看到来源。
            if (finalConversationId != null) {
                conversationService.addMessage(finalConversationId, currentUserId,
                        "assistant", result.getAnswer(), result.getSources());
            }

            Map<String, Object> data = new HashMap<>();
            data.put("question", question);
            data.put("answer", result.getAnswer());
            // 前端提交答案评价时要靠它关联到这次提问
            data.put("questionLogId", result.getQuestionLogId());
            // **前端靠它续接下一问**。不返回的话，走这条路的提问永远学不到会话 id，
            // 下一次提问又会另起一个会话 —— 历史记录里就散成一堆。
            data.put("conversationId", finalConversationId);
            // 答案来源标注：null/空时前端不渲染「来源」区。走兜底/工具调用等无检索路径时为 null。
            data.put("sources", result.getSources());
            return Response.success(data);
        } catch (BizException e) {
            // 会话不存在或不属于该用户 —— 单独接住，别让它掉进下面的通用分支
            // 变成"处理失败: 会话不存在"（那会把一个 404 语义伪装成服务端故障）
            log.warn("会话校验未通过: {}", e.getMessage());
            return Response.fail(ErrorCode.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            log.error("❌ RAG问答失败: {}", e.getMessage(), e);
            // 失败也要留痕，否则看板上看不到任何异常，问题会被静默吞掉。
            // 复用上面已经查到的 currentUser —— 失败路径反而比原来少查一次库。
            try {
                questionLogService.record(question,
                        currentUserId,
                        finalConversationId, KbQuestionLog.HIT_ERROR, 0, null, clientIp);
            } catch (Throwable t) {
                log.warn("异常埋点写入失败: {}", t.getMessage());
            }
            // 保持与原行为一致的"处理失败但请求本身成功"语义：HTTP 200 + success:false
            return Response.fail(ErrorCode.INTERNAL_ERROR, "处理失败: " + e.getMessage());
        } finally {
            // **名额唯一的释放点。** 上面有四五个 return，还有异常路径 ——
            // 逐个 close 迟早会漏，而漏一次就永久少一个名额（闸门会越来越早地喊"人数较多"，
            // 而且不报任何错）。finally 是唯一不会漏的写法。
            permit.close();
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

        // 并发名额：**在配额之前拿**。反过来的话，服务器忙时被拒的请求会白扣一次配额 ——
        // 用户什么都没得到，当日免费额度却少了一条。
        final RagConcurrencyLimiterI.Permit permit;
        try {
            permit = concurrencyLimiter.acquire(
                    currentUser == null ? null : currentUser.getId(), clientIp);
        } catch (BizException e) {
            return Flux.just(e.getMessage(), "[DONE]");
        }

        // 从配额判定到建流这一段，**任何一条失败路径都必须把名额还回去**。
        // 漏一处就少一个名额，而且不报错 —— 只是闸门越来越早地喊"人数较多"，
        // 要等到有人抱怨"明明没人用却总说忙"才会被发现。所以统一收口在这里。
        final Long currentUserId = currentUser == null ? null : currentUser.getId();
        // 客户公司归属，用于会话隔离标记（P0：会话列表仍按 userId 隔离，clientId 为 P1 铺路）
        final Long currentCompanyId = currentUser == null ? null : currentUser.getCompanyId();
        final String finalConversationId;
        final RagServiceI.AnswerStream answerStream;
        try {
            // 准入判定：匿名按 IP、会员放行、非会员按每日免费额度。超限也只能走 SSE
            // （本方法返回 Flux，包不了 Response 信封），沿用上面鉴权失败的同一写法。
            chatAccessGuard.checkAndRecord(anonymous, currentUser, clientIp);

            log.info("📨 收到流式RAG问答请求: {}（{}）", question, anonymous ? "匿名" : "已登录");

            // 匿名用户不做会话持久化（MongoDB 里不留无主会话），所以直接置空
            finalConversationId = anonymous
                    ? null
                    : resolveConversationId(conversationId, question, currentUserId, currentCompanyId);

            // 先把流和埋点 id 一起拿到，**再**拼 meta 帧 ——
            // 顺序反过来 meta 帧里就拿不到 questionLogId 了（它是在 service 里落库产生的）。
            answerStream = ragService.chatWithDocumentStream(question, finalConversationId, clientIp);
        } catch (BizException e) {
            // 会话不存在或不属于该用户。本方法返回 Flux，不能把异常抛出去 ——
            // 那样客户端在流式响应里会收到一个 JSON 错误体，解析直接乱掉。
            // 沿用本项目既有的写法：把消息当成一帧内容发出去。
            permit.close();
            log.warn("流式问答前置检查未通过: {}", e.getMessage());
            return Flux.just(e.getMessage(), "[DONE]");
        } catch (Throwable t) {
            // 非业务异常（Mongo 挂了之类）在 return Flux 之前抛出是安全的 ——
            // 此时还没开始写 SSE，全局处理器能正常返回 JSON 错误体。但名额要还。
            permit.close();
            throw t;
        }

        // 4. 准备AI回答的收集器
        StringBuilder aiAnswer = new StringBuilder();

        // 5. 构建流式响应
        //    meta 帧带上 sources（答案来源标注），前端在回答流之前就拿到来源信息。
        //    用 ObjectMapper 序列化，避免手动拼 JSON 时标题/片段里的引号、换行转义出错。
        //    序列化失败时退回不含 sources 的旧格式 —— meta 帧断了整条流就废了，不能冒险。
        String metaJson;
        try {
            Map<String, Object> meta = new HashMap<>();
            meta.put("type", "meta");
            meta.put("conversationId", finalConversationId);
            meta.put("anonymous", anonymous);
            meta.put("questionLogId", answerStream.getQuestionLogId());
            meta.put("sources", answerStream.getSources());
            metaJson = objectMapper.writeValueAsString(meta);
        } catch (JsonProcessingException e) {
            log.warn("meta 帧序列化失败，退回不含 sources 的版本: {}", e.getMessage());
            metaJson = "{\"type\":\"meta\",\"conversationId\":"
                    + (finalConversationId == null ? "null" : "\"" + finalConversationId + "\"")
                    + ",\"anonymous\":" + anonymous
                    + ",\"questionLogId\":" + answerStream.getQuestionLogId() + "}";
        }
        Flux<String> metaDataFlux = Flux.just(metaJson);

        final Long questionLogId = answerStream.getQuestionLogId();

        Flux<String> aiStream = answerStream.getContent()
                .doOnNext(chunk -> aiAnswer.append(chunk))
                .doOnComplete(() -> {
                    if (finalConversationId != null) {
                        // sources 随消息持久化，历史记录重进仍能看到「来源」展开区
                        conversationService.addMessage(finalConversationId, currentUserId,
                                "assistant", aiAnswer.toString(), answerStream.getSources());
                        log.info("✅ AI回答已保存，会话ID: {}", finalConversationId);
                    }
                })
                .doOnError(e -> log.error("流式问答失败", e));

        // 合并两个流：先发送 meta，再发送 AI 流。
        //
        // **doFinally 是名额唯一的释放点** —— complete / error / cancel 三种终止都会走到它。
        // 分散在别处释放迟早会漏，而漏一次就永久少一个名额。
        //
        // 客户端点「取消」时前端 abort，Spring 取消这条链 → WebClient 断开与 Ollama 的连接
        // → 模型真的停止生成。这就是"取消能减轻服务器负载"的机制，不需要额外做什么；
        // 反过来说，只要断开链路是通的，就别在服务端加"主动杀 Ollama"的逻辑（那会误伤）。
        return Flux.concat(metaDataFlux, aiStream)
                .doOnCancel(() -> {
                    log.info("🚫 客户端取消提问：已生成 {} 字，logId={}", aiAnswer.length(), questionLogId);
                    // 半截答案不入库（doOnComplete 不会触发），但埋点要落成 CANCELLED ——
                    // 否则看板上这次提问永远停在"已完成"，latency_ms 也一直是 NULL
                    questionLogService.markStatus(questionLogId, KbQuestionLog.STATUS_CANCELLED);
                })
                .doFinally(signal -> permit.close());
    }

    /**
     * 取出或新建会话，并把用户提问写入会话。仅已登录用户调用。
     *
     * <p>传入已有 conversationId 时，归属校验在 service 层做 —— 不属于该用户会抛
     * {@link BizException}，由调用方转成 SSE 错误帧。
     */
    private String resolveConversationId(String conversationId, String question, Long userId, Long clientId) {
        if (conversationId != null && !conversationId.isEmpty()) {
            conversationService.addMessage(conversationId, userId, "user", question);
            log.info("🔁 使用已有会话，ID: {}", conversationId);
            return conversationId;
        }
        Conversation conv = conversationService.createConversation(userId, question, clientId);
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