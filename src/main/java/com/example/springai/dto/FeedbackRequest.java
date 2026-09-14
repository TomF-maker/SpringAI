package com.example.springai.dto;

import lombok.Data;

/**
 * 提交答案评价。
 */
@Data
public class FeedbackRequest {

    /** 关联的提问日志 id。由 /api/rag/chat 的响应或流式的 meta 帧透传给前端。 */
    private Long questionLogId;

    private String conversationId;

    /** 提问原文。冗余存一份，审核页展示时不用再回查。 */
    private String question;

    /** 被评价的回答原文。 */
    private String answer;

    /** {@link com.example.springai.common.AnswerRating} 的枚举名。 */
    private String rating;

    /**
     * 优化意见。仅当 rating 是「帮助不大 / 完全没用」时才需要，
     * 其他档位传了也会被忽略。
     */
    private String suggestion;
}
