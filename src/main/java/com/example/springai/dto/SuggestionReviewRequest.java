package com.example.springai.dto;

import lombok.Data;

/**
 * 管理员审核优化意见。
 */
@Data
public class SuggestionReviewRequest {

    /** ACCEPT = 采纳并加积分；REJECT = 驳回。 */
    private String action;

    /** 奖励档位：{@link com.example.springai.common.PointsRewardTier} 的枚举名。 */
    private String tier;

    /** 自定义积分数。填了它就以它为准，忽略 tier。 */
    private Integer customPoints;

    /** 审核备注，会展示给提建议的人。 */
    private String remark;
}
