package com.example.springai.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会员状态。个人中心卡片和 {@code /api/rag/chat/quota} 都用它。
 *
 * <p>{@code active} 由后端那个唯一的判定函数算（{@code SysUser.hasActiveMembership}），
 * **不要把判定下沉到前端** —— 否则前端又会出现一处 {@code memberType != null} 的近似实现。
 */
@Data
public class MembershipStatusDTO {

    private Long userId;
    /** MembershipPlan 的枚举名；null = 非会员。 */
    private String memberType;
    /** 中文名，前端直接展示。 */
    private String memberTypeLabel;
    private LocalDateTime memberExpireAt;
    /** 当前是否有效会员（已把过期与脏数据都算进去）。 */
    private boolean active;
    /**
     * 是否永久会员。永久会员**不能再兑换任何有限档位** ——
     * 前端据此隐藏兑换按钮，服务端也会拒绝。放这里是为了让判定只有一处，
     * 前端不用自己去比 'PERMANENT' 字符串（那样两边迟早漂移）。
     */
    private boolean permanent;
    /** 距到期天数；永久会员与非会员为 null。 */
    private Long remainingDays;
    /** 积分余额。 */
    private Integer points;
    /** 非会员的每日免费额度（有效会员为 null）。 */
    private Long freeDailyLimit;
    /** 非会员当日剩余免费额度（有效会员为 null）。 */
    private Long freeRemaining;
}
