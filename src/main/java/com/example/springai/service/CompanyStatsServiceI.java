package com.example.springai.service;

import com.example.springai.dto.CompanyStatisticsDTO;

/**
 * 「每个公司多少人」的统计。
 *
 * <p>统计维度是 {@code sys_company}（JOIN {@code sys_user.company_id}），不是部门 ——
 * 公司是独立实体，见 {@code doc/schema.sql} 里那段说明。
 */
public interface CompanyStatsServiceI {

    /** 查询不可用时返回 {@code unavailable = true} 的空结果，不抛异常。 */
    CompanyStatisticsDTO getCompanyStatistics();
}
