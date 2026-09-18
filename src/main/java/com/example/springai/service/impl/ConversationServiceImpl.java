package com.example.springai.service.impl;

import com.example.springai.common.ErrorCode;
import com.example.springai.entity.Conversation;
import com.example.springai.entity.Message;
import com.example.springai.entity.SourceRef;
import com.example.springai.exception.BizException;
import com.example.springai.repository.ConversationRepository;
import com.example.springai.service.ConversationServiceI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class ConversationServiceImpl implements ConversationServiceI {

    @Autowired
    private ConversationRepository conversationRepository;

    /** 归属不匹配时的统一文案：不区分"不存在"与"无权访问"。 */
    private static final String NOT_FOUND_MESSAGE = "会话不存在";

    public Conversation createConversation(Long userId, String firstQuestion) {
        return createConversation(userId, firstQuestion, null);
    }

    @Override
    public Conversation createConversation(Long userId, String firstQuestion, Long clientId) {
        Conversation conv = new Conversation();
        conv.setUserId(userId);
        conv.setClientId(clientId);
        conv.setTitle(generateTitle(firstQuestion));
        conv.setCreatedAt(LocalDateTime.now());
        conv.setUpdatedAt(LocalDateTime.now());
        return conversationRepository.save(conv);
    }

    @Override
    public Conversation addMessage(String conversationId, Long userId, String role, String content) {
        return addMessage(conversationId, userId, role, content, null);
    }

    @Override
    public Conversation addMessage(String conversationId, Long userId, String role, String content,
                                   List<SourceRef> sources) {
        Conversation conv = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, NOT_FOUND_MESSAGE));
        if (!isOwner(conv, userId)) {
            log.warn("拒绝向非本人会话追加消息: conversationId={}, 请求人={}, 会话归属={}",
                    conversationId, userId, conv.getUserId());
            throw new BizException(ErrorCode.NOT_FOUND, NOT_FOUND_MESSAGE);
        }
        // sources 随 assistant 消息持久化 —— 历史记录重进时仍能渲染「来源」展开区
        conv.getMessages().add(new Message(role, content, LocalDateTime.now(), sources));
        conv.setUpdatedAt(LocalDateTime.now());
        return conversationRepository.save(conv);
    }

    @Override
    public List<Conversation> getUserConversations(Long userId) {
        // 走投影查询（不含消息里的 sources）而不是全量查询：列表页用不到来源片段，
        // 但它是消息体积的大头，一次拉全部会话时尤其明显。
        // 别改回 findByUserIdOrderByUpdatedAtDesc —— 那会把每个会话的来源片段都搬回前端。
        return conversationRepository.findListByUserId(userId);
    }

    @Override
    public Conversation getConversation(String id, Long userId) {
        Conversation conv = conversationRepository.findById(id).orElse(null);
        if (!isOwner(conv, userId)) {
            if (conv != null) {
                log.warn("拒绝读取非本人会话: conversationId={}, 请求人={}, 会话归属={}",
                        id, userId, conv.getUserId());
            }
            return null;
        }
        return conv;
    }

    @Override
    public boolean deleteConversation(String id, Long userId) {
        Conversation conv = conversationRepository.findById(id).orElse(null);
        if (!isOwner(conv, userId)) {
            if (conv != null) {
                log.warn("拒绝删除非本人会话: conversationId={}, 请求人={}, 会话归属={}",
                        id, userId, conv.getUserId());
            }
            return false;
        }
        conversationRepository.deleteById(id);
        return true;
    }

    /**
     * 归属判定。包级可见是为了能直接单测这条规则。
     *
     * <p>两种情况都判为<b>不通过</b>：
     * <ul>
     *   <li>{@code userId == null}（匿名调用）—— 匿名没有可靠身份，不该读到任何人的会话；</li>
     *   <li>{@code conv.getUserId() == null}（无主会话）—— 这种会话没有归属人，
     *       如果放行等于任何人都能读到，所以一律不通过。</li>
     * </ul>
     */
    static boolean isOwner(Conversation conv, Long userId) {
        if (conv == null || userId == null) {
            return false;
        }
        return userId.equals(conv.getUserId());
    }

    private String generateTitle(String firstQuestion) {
        if (firstQuestion == null || firstQuestion.isEmpty()) {
            return "新对话";
        }
        // 截取前20个字符作为标题
        return firstQuestion.length() > 20 ? firstQuestion.substring(0, 20) + "..." : firstQuestion;
    }
}
