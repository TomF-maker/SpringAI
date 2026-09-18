package com.example.springai.service;

import com.example.springai.dto.ClientAdminStatsDTO;

/**
 * 客户公司看板（客户管理员看自己的公司）。
 *
 * <p><b>每个方法都必须接收 clientId</b>，没有"不传 clientId 就查全部"的重载 ——
 * 和 {@code ConversationServiceI} 同一个理由：一旦提供无参版本，迟早有人从
 * 别的入口调到它，然后一个客户看到全平台的数字。调用方（Controller）
 * 只能从当前登录用户的 {@code companyId} 取，绝不能从请求参数取。
 */
public interface ClientAdminStatsServiceI {

    /**
     * 查本公司的看板数据。
     *
     * <p>查询不可用时返回 {@code unavailable = true} 的空结果，不抛异常。
     *
     * @param clientId 客户公司 id（{@code sys_company.id}），取自登录用户，绝不是请求参数
     * @param days     统计窗口天数
     * @param topN     热门问题与活跃榜各取前 N 条
     */
    ClientAdminStatsDTO getStats(Long clientId, int days, int topN);
}
