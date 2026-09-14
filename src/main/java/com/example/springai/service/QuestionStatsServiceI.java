package com.example.springai.service;

import com.example.springai.dto.QuestionStatisticsDTO;

/**
 * 数据看板的提问类统计。
 */
public interface QuestionStatsServiceI {

    /**
     * 汇总提问数据。
     *
     * @param days 统计窗口天数（趋势、活跃用户、人均、命中率都用它）
     * @param topN 热门问题取前 N 条
     */
    QuestionStatisticsDTO getQuestionStatistics(int days, int topN);
}
