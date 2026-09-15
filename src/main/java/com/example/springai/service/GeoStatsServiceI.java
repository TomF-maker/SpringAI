package com.example.springai.service;

import com.example.springai.dto.GeoStatisticsDTO;

/**
 * 地域大屏的统计：登录 / 提问分别来自哪个省、市。
 *
 * <p>两个数据源结构一样但表不同（{@code kb_login_log} 低频、{@code kb_question_log} 高频），
 * 所以是两个方法而不是一个带枚举参数的 —— 注册表差异留在实现里，接口上看得见。
 */
public interface GeoStatsServiceI {

    /** 提问的地域分布。查询不可用时返回 {@code unavailable = true}，不抛异常。 */
    GeoStatisticsDTO getQuestionGeo(int days, int topN);

    /** 登录的地域分布。 */
    GeoStatisticsDTO getLoginGeo(int days, int topN);
}
