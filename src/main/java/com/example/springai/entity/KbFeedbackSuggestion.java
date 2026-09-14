package com.example.springai.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 回答优化意见。由用户在打低分（帮助不大 / 完全没用）时填写，管理员审核。
 * 采纳后给提建议的人发积分。
 */
@Data
@TableName("kb_feedback_suggestion")
public class KbFeedbackSuggestion {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_REJECTED = "REJECTED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long feedbackId;
    /** 提建议的人；被采纳后积分加给他。 */
    private Long userId;
    private String content;
    private String status;
    /** 采纳后实际发放的积分（档位换算或自定义）。 */
    private Integer awardedPoints;
    private String reviewRemark;
    private Long reviewerId;
    private LocalDateTime reviewedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
