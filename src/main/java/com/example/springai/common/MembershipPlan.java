package com.example.springai.common;

import com.example.springai.exception.BizException;

import java.time.LocalDateTime;
import java.util.Arrays;

/**
 * 会员档位。
 *
 * <p><b>价格用穷尽 {@code switch} 而不是 Map 或字段默认值</b>，这是刻意的：
 * 如果把价格放 {@code Map} 或者写一个返回 {@code int} 的 getter，
 * {@code PERMANENT} 漏填时默认就是 0 —— 那意味着**扣 0 分白送永久会员**。
 * 用 switch 表达式之后，以后加档位不写 case 直接编译不过。
 *
 * <p>{@code PERMANENT} 按产品要求**不开放积分兑换**，只能由运营发放。
 *
 * <p>时长用<b>日历单位</b>而不是天数：月度是 {@code plusMonths(1)} 而不是 "+30 天"，
 * 否则 2 月 1 日买的会员会在 3 月 2 日（闰年 3 月 3 日）到期。
 */
public enum MembershipPlan {

    MONTHLY("月度会员", "1 个月"),
    QUARTERLY("季度会员", "3 个月"),
    YEARLY("年度会员", "1 年"),
    PERMANENT("永久会员", "永久");

    private final String label;
    private final String durationLabel;

    MembershipPlan(String label, String durationLabel) {
        this.label = label;
        this.durationLabel = durationLabel;
    }

    public String getLabel() {
        return label;
    }

    public String getDurationLabel() {
        return durationLabel;
    }

    /** 是否可以拿积分兑换。前端拿它置灰按钮，服务端拿它做校验，**同源**。 */
    public boolean isExchangeable() {
        return this != PERMANENT;
    }

    /**
     * 兑换所需积分。
     *
     * @throws BizException 永久会员不开放兑换
     */
    public int pointsCost() {
        return switch (this) {
            case MONTHLY -> 500;
            case QUARTERLY -> 1200;
            case YEARLY -> 4000;
            case PERMANENT -> throw new BizException(ErrorCode.BAD_REQUEST,
                    "永久会员不对积分开放，请联系管理员");
        };
    }

    /**
     * 从某个基准时间往后推一个档位时长。
     *
     * <p>调用方负责传对基准：续期时传 {@code max(now, 当前到期时间)}，
     * 让用户没用完的时长不被吃掉。
     *
     * @return 永久会员返回 null（没有到期时间）
     */
    public LocalDateTime plusFrom(LocalDateTime base) {
        return switch (this) {
            case MONTHLY -> base.plusMonths(1);
            case QUARTERLY -> base.plusMonths(3);
            case YEARLY -> base.plusYears(1);
            case PERMANENT -> null;
        };
    }

    /**
     * 解析档位名。
     *
     * @return 无法识别时返回 null（调用方应拒绝请求，不要默认成某一档）
     */
    public static MembershipPlan parse(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String v = name.trim().toUpperCase();
        return Arrays.stream(values())
                .filter(p -> p.name().equals(v))
                .findFirst()
                .orElse(null);
    }
}
