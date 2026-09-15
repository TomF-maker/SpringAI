package com.example.springai.dto;

import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * 地域大屏的数据（登录或提问，同一套结构）。
 *
 * <p><b>{@link #unresolved} 和 {@link #unmapped} 必须都在 DTO 里暴露出去。</b>
 * 它们分别对应两种"数字对不上"的情况，而两种都会让地图看起来只是"有些地方没数据"：
 * <ul>
 *   <li>{@link #unresolved} —— 压根没有省份信息。IP 是内网、取不到，
 *       或者归属地还没异步补写完。高德覆盖不全，这类会占相当比例。</li>
 *   <li>{@link #unmapped} —— 有省份，但那个名字不在 ECharts 中国地图的 34 个名字里。
 *       出现非零值基本意味着名称归一化漏了一种写法，是**代码问题不是数据问题**。</li>
 * </ul>
 * 只显示"广东占 40%"而不显示这两个数，看的人会以为剩下 60% 是境外流量。
 */
@Data
public class GeoStatisticsDTO {

    private int days;
    /** 统计不可用（表或列缺失）。前端应显示提示而不是画一张空图。 */
    private boolean unavailable;

    /** 统计窗口内的总记录数。 */
    private long total;
    /** 有省份信息的记录数（= total - unresolved）。 */
    private long resolved;
    /** 没有省份信息的记录数。 */
    private long unresolved;
    /** 有省份但归一化后不在中国地图 34 个名字里的记录数。非零 = 归一化漏了写法。 */
    private long unmapped;

    /** 省级分布，{@code name} 已归一化成地图认得的简称（如「广东」），已按人数倒序合并。 */
    private List<NameCount> provinces = Collections.emptyList();

    /** 城市 Top-N，{@code name} 是原始市名（如「深圳市」），没有地图所以不做归一化。 */
    private List<NameCount> cities = Collections.emptyList();
}
