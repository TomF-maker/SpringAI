package com.example.springai.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会员变更日志。
 *
 * <p>为什么单开一张表：{@code kb_points_log} 只记"扣了多少分"，
 * 记不了"会员怎么变的"。用户来问"我的会员怎么没了"时得答得上来。
 */
@Data
@TableName("kb_membership_log")
public class KbMembershipLog {

    /** 用户用积分自助兑换。 */
    public static final String ACTION_EXCHANGE = "EXCHANGE";
    /** 管理员发放/调整。 */
    public static final String ACTION_ADMIN_GRANT = "ADMIN_GRANT";
    /** 定时任务清理到期会员。 */
    public static final String ACTION_EXPIRE = "EXPIRE";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String action;
    /** 本次动作**之后的结果档位**；EXPIRE 为 NULL。（与 sys_user 语义一致，别存"旧值"） */
    private String memberType;
    /** 本次动作**之后**的到期时间。 */
    private LocalDateTime expireAt;
    /** 消耗的积分；非兑换动作为 0。 */
    private Integer pointsCost;
    private String remark;
    /** 操作人；用户自助兑换与系统清理为 NULL。 */
    private Long operatorId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
