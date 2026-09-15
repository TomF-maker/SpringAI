package com.example.springai.dto;

import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * 「每个公司多少人」的统计结果。
 *
 * <p>{@link #companies} 里会有一行 {@code name = "未填写"} —— 那是本功能上线前注册的
 * 老用户（{@code company_id} 为 NULL）。<b>刻意保留而不是过滤掉</b>：
 * 各公司人数之和要对得上 {@link #totalUsers}，否则报表上会有人问"剩下的呢"。
 * 它不算在 {@link #companyCount} 里 —— 它不是一个公司。
 */
@Data
public class CompanyStatisticsDTO {

    /** 启用中的用户总数。各公司人数之和应当等于它。 */
    private long totalUsers;
    /** 有公司信息的公司数（不含"未填写"那一行）。 */
    private long companyCount;
    /** 每家公司的人数，按人数倒序。 */
    private List<NameCount> companies = Collections.emptyList();

    /**
     * 统计不可用（表或列缺失等）。前端据此显示提示而不是画一张空图 ——
     * 空图会让人以为"真的没有公司"，而实际是查不了。
     */
    private boolean unavailable;
}
