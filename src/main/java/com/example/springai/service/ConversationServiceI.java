package com.example.springai.service;

import com.example.springai.entity.Conversation;
import com.example.springai.entity.SourceRef;

import java.util.List;

/**
 * 会话历史。
 *
 * <p><b>所有按 id 操作的方法都必须带 userId 并做归属校验</b>，没有不带校验的重载 ——
 * 早先的 {@code getConversation(String)} / {@code deleteConversation(String)} 是裸的
 * {@code findById}，任何登录用户拿到别人的会话 id 就能读、能删、能往里面追加消息，
 * 而会话 id 会随 {@code /chat?conversationId=xxx} 这种链接扩散出去。
 * 所以这里刻意不提供无校验版本，避免以后又被误用。
 *
 * <p>归属不匹配时统一按"不存在"处理（返回 null / false），不区分"不存在"和"无权访问" ——
 * 否则反复试 id 就能探出哪些会话是真实存在的。
 */
public interface ConversationServiceI {

    Conversation createConversation(Long userId, String firstQuestion);

    /**
     * 创建会话并标记客户归属。
     *
     * @param clientId 客户公司 id（{@code sys_company.id}）；匿名或无公司为 null。
     *                  P0 阶段会话列表仍按 userId 隔离，本字段为 P1 客户管理员后台铺路。
     */
    Conversation createConversation(Long userId, String firstQuestion, Long clientId);

    /**
     * 追加一条消息。会校验会话归属。
     *
     * @throws com.example.springai.exception.BizException 会话不存在或不属于该用户
     */
    Conversation addMessage(String conversationId, Long userId, String role, String content);

    /**
     * 追加一条带答案来源标注的 assistant 消息。
     *
     * <p>来源标注随消息一起持久化，用户从历史记录点进去仍能看到「来源」展开区。
     * user 消息不传 sources（传 null 走上面的重载即可）。
     *
     * @param sources 检索命中的文档片段来源标注；无检索路径（兜底/工具调用）传 null
     */
    Conversation addMessage(String conversationId, Long userId, String role, String content, List<SourceRef> sources);

    List<Conversation> getUserConversations(Long userId);

    /**
     * 取会话详情。
     *
     * @return 会话不存在、或不属于该用户时返回 null
     */
    Conversation getConversation(String id, Long userId);

    /**
     * 删除会话。
     *
     * @return 会话不存在、或不属于该用户时返回 false
     */
    boolean deleteConversation(String id, Long userId);
}
