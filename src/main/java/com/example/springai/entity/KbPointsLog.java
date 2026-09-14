package com.example.springai.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 积分流水。
 *
 * <p>{@code sys_user.points} 是余额，这张表是审计与对账依据 ——
 * 只改余额不记流水的话，一旦对不上就无从查起。
 * {@code balanceAfter} 冗余存了变更后的余额，就是为了能直接和余额字段对账。
 */
@Data
@TableName("kb_points_log")
public class KbPointsLog {

    /** 优化意见被采纳。 */
    public static final String REASON_SUGGESTION_ACCEPTED = "SUGGESTION_ACCEPTED";
    /** 管理员手工调整。 */
    public static final String REASON_ADMIN_ADJUST = "ADMIN_ADJUST";
    /** 兑换会员（本期未实现，先占位）。 */
    public static final String REASON_EXCHANGE_MEMBERSHIP = "EXCHANGE_MEMBERSHIP";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** 正数增加，负数消费。 */
    private Integer changeAmount;
    private Integer balanceAfter;
    private String reason;
    /** 关联业务 id，如建议 id。 */
    private Long refId;
    private String remark;
    /** 操作人；系统发放为 NULL。 */
    private Long operatorId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
