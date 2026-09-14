package com.example.springai.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理员发放/调整会员。
 */
@Data
public class MembershipGrantRequest {

    /**
     * 档位名（{@link com.example.springai.common.MembershipPlan} 的枚举名）。
     * <b>传空表示取消会员。</b>
     */
    private String memberType;

    /**
     * 指定到期时间；为空时按档位时长从当前时间（或现有到期时间）推算。
     * 永久会员会忽略它并强制置 NULL。
     */
    private LocalDateTime expireAt;

    private String remark;
}
