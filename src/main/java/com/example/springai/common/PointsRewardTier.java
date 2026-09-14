package com.example.springai.common;

import java.util.Arrays;

/**
 * 采纳优化意见时的积分奖励档位。
 *
 * <p>定这三档是为了让管理员审核时有"一键"选项，不用每次手填数字；
 * 同时自定义金额也支持（有些建议确实不好归到某一档）。
 *
 * <p>定这个量级的依据：月度会员 500 分（见 {@code doc/schema.sql} 里的定价约定），
 * 也就是**5 条"有价值"的建议换一个月会员**。太低会让积分迅速贬值，
 * 太高则没人愿意提建议。
 */
public enum PointsRewardTier {

    SMALL("小改进", 50),
    MEDIUM("有价值", 100),
    LARGE("重大改进", 200);

    private final String label;
    private final int points;

    PointsRewardTier(String label, int points) {
        this.label = label;
        this.points = points;
    }

    public String getLabel() {
        return label;
    }

    public int getPoints() {
        return points;
    }

    public static PointsRewardTier parse(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String v = name.trim().toUpperCase();
        return Arrays.stream(values())
                .filter(t -> t.name().equals(v))
                .findFirst()
                .orElse(null);
    }
}
