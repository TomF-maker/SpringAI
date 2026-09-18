package com.example.springai.repository;

import com.example.springai.entity.Conversation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;

public interface ConversationRepository extends MongoRepository<Conversation, String> {

    /**
     * 会话列表专用查询：**排除每条消息里的 {@code sources}（答案来源片段）**。
     *
     * <p>历史页真正用到的只有 title / createdAt / updatedAt，以及 messages 里的
     * role + content（列表预览、"提问轮数"、关键词搜索都只读这两个字段）。
     * 而 {@code sources} 里装的是检索命中的**原文片段**（每条片段几百字，一轮问答 3 条），
     * 是消息体积的大头 —— 列表要一次拉出某人全部会话，带着它等于把知识库摘要搬一遍。
     *
     * <p>曾用过不投影的 {@code findByUserIdOrderByUpdatedAtDesc}：
     * 几十个会话时看不出问题，攒到几百个之后这一页就是几 MB 的 JSON 和白读的 IO。
     *
     * <p>详情接口（{@code findById}）**不投影**，所以点进某个会话仍能看到来源。
     */
    @Query(value = "{ 'userId': ?0 }",
            fields = "{ 'messages.sources': 0 }",
            sort = "{ 'updatedAt': -1 }")
    List<Conversation> findListByUserId(Long userId);
}
