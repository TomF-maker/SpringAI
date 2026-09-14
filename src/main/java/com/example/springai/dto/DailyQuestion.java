package com.example.springai.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 每日提问趋势的一个数据点。
 *
 * <p>字段形状对齐现有的 {@link DailyUpload}（date 为 String），
 * 前端 ECharts 折线图的画法可以直接复用。
 */
@Data
@AllArgsConstructor
public class DailyQuestion {
    private String date;
    private long count;
    /** 当天提问过的去重用户数。 */
    private long activeUsers;
}
