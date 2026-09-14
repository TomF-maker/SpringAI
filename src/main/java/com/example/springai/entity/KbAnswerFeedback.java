package com.example.springai.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 答案帮助度评价。一次提问最多一条。
 *
 * <p>{@code questionLogId} 指向 {@code kb_question_log.id} —— 这个关联是刻意的：
 * 有了它才能回答"哪类问题容易被打低分"（比如命中类型是 MISS 的、或者检索到 0 篇的），
 * 而不是只知道"有人不满意"。所以 {@code RagService} 会把提问日志 id 一路透传到前端。
 */
@Data
@TableName("kb_answer_feedback")
public class KbAnswerFeedback {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 评价人。匿名用户不能评价，所以这里不会为 NULL。 */
    private Long userId;
    private Long questionLogId;
    private String conversationId;
    private String question;
    private String answer;
    /** {@link com.example.springai.common.AnswerRating} 的枚举名。 */
    private String rating;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
