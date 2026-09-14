package com.example.springai.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理端审核列表的一行。
 *
 * <p>把评价、建议、提问日志三段信息拼在一起，审核页一次请求就能拿到全部上下文 ——
 * 管理员判断"这条建议合不合理"需要看到**原问题和原回答**，
 * 只看一句"回答不准确"是没法判断的。
 */
@Data
public class PendingSuggestionDTO {

    // ---------- 建议 ----------
    private Long id;
    private String content;
    private String status;
    private Integer awardedPoints;
    private String reviewRemark;
    private LocalDateTime reviewedAt;
    private LocalDateTime createdAt;

    // ---------- 提建议的人 ----------
    private Long userId;
    private String username;
    private String realName;
    /** 提建议的人当前积分余额。 */
    private Integer userPoints;

    // ---------- 被评价的回答 ----------
    private Long feedbackId;
    private Long questionLogId;
    private String conversationId;
    private String question;
    private String answer;
    /** 评价档位（帮助度五档的枚举名）。 */
    private String rating;
    /** 评价档位的中文名，前端直接展示。 */
    private String ratingLabel;

    // ---------- 提问日志里带的分析维度 ----------
    /** LOCAL / DOC / MISS / TOOL —— 有了它才能看出"哪类回答容易被打低分"。 */
    private String hitType;
    private Integer retrievedCount;
    private Long latencyMs;
}
