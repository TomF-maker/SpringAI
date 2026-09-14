package com.example.springai.common;

import java.util.Arrays;

/**
 * 答案帮助度评价（五档）。
 *
 * <p>围绕"这个答案帮到你了吗"来问，而不是"你满意吗" —— 后者容易把
 * "答案很客气但没用"评成满意，对改进知识库没有指导意义。
 *
 * <p>{@code score} 是有序的（5 → 1），所以以后可以直接算"平均帮助度"
 * 或做趋势图，不用把五档硬编码成 if-else。
 *
 * <p>后两档会引导用户填写优化意见（见 {@code needsSuggestion}）——
 * 只有负向反馈才值得追问原因；打了"很有帮助"还弹框问哪里可以改进是打扰。
 */
public enum AnswerRating {

    VERY_HELPFUL("很有帮助", 5, false),
    HELPFUL("比较有帮助", 4, false),
    NEUTRAL("一般", 3, false),
    NOT_HELPFUL("帮助不大", 2, true),
    USELESS("完全没用", 1, true);

    private final String label;
    private final int score;
    private final boolean needsSuggestion;

    AnswerRating(String label, int score, boolean needsSuggestion) {
        this.label = label;
        this.score = score;
        this.needsSuggestion = needsSuggestion;
    }

    public String getLabel() {
        return label;
    }

    /** 5 = 很有帮助，1 = 完全没用。用于算平均分/趋势。 */
    public int getScore() {
        return score;
    }

    /** 是否引导用户填写优化意见。 */
    public boolean isNeedsSuggestion() {
        return needsSuggestion;
    }

    /**
     * 解析前端传来的枚举名。
     *
     * @return 无法识别时返回 null（调用方应拒绝该请求，不要默认成某一档 ——
     *         默认成"很有帮助"会污染数据）
     */
    public static AnswerRating parse(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String v = name.trim().toUpperCase();
        return Arrays.stream(values())
                .filter(r -> r.name().equals(v))
                .findFirst()
                .orElse(null);
    }
}
