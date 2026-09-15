package com.example.springai.common;

import java.util.List;
import java.util.Set;

/**
 * 省名归一化：把 IP 归属地里的**全称**转成 ECharts 中国地图的**简称**。
 *
 * <p><b>这是整个地域大屏最容易被忽略、且失败得最安静的一环。</b>
 * 高德返回的是 {@code 广东省}、{@code 北京市}、{@code 内蒙古自治区}，
 * 而 ECharts 的 {@code china.json}（34 个省级 feature）里登记的是
 * {@code 广东}、{@code 北京}、{@code 内蒙古}。
 * 名字对不上时 ECharts <b>不报错、不告警</b>，只是那块区域不着色 ——
 * 整张地图一片灰，看起来像"没有数据"，而不是"数据名字写错了"。
 *
 * <p>所以归一化放在后端做一次，由 {@code GeoStatisticsService} 统一调用，
 * 并且有 {@code RegionNamesTest} 锁住几个不规则的（内蒙古/广西/新疆/宁夏/西藏/港澳）。
 */
public final class RegionNames {

    /**
     * 要剥掉的后缀，<b>必须按长度从长到短</b>。
     *
     * <p>顺序反了就会出错：先匹配到 {@code 自治区} 的话，
     * {@code 广西壮族自治区} 会变成 {@code 广西壮族} —— 地图里没有这个名字，同样是静默不着色。
     */
    private static final List<String> SUFFIXES = List.of(
            "特别行政区",
            "维吾尔自治区",
            "壮族自治区",
            "回族自治区",
            "自治区",
            "省",
            "市"
    );

    /** ECharts 中国地图 GeoJSON 里登记的全部省级名称（34 个）。 */
    private static final Set<String> MAP_PROVINCES = Set.of(
            "北京", "天津", "河北", "山西", "内蒙古", "辽宁", "吉林", "黑龙江",
            "上海", "江苏", "浙江", "安徽", "福建", "江西", "山东", "河南",
            "湖北", "湖南", "广东", "广西", "海南", "重庆", "四川", "贵州",
            "云南", "西藏", "陕西", "甘肃", "青海", "宁夏", "新疆",
            "台湾", "香港", "澳门"
    );

    private RegionNames() {
    }

    /**
     * 转成地图认得的名字。已经是简称的原样返回。
     *
     * @return 简称；入参为 null 或空白时返回 null
     */
    public static String toMapName(String province) {
        if (province == null) {
            return null;
        }
        String value = province.trim();
        if (value.isEmpty()) {
            return null;
        }
        for (String suffix : SUFFIXES) {
            // 长度判断是防止 "省" 这种输入被削成空串
            if (value.endsWith(suffix) && value.length() > suffix.length()) {
                return value.substring(0, value.length() - suffix.length());
            }
        }
        return value;
    }

    /**
     * 归一化之后是不是地图上认得的省。
     *
     * <p>用来在服务层发现"名字拼对了但地图没有"的情况（比如某个直辖市的新写法、
     * 或境外返回的国家名混进了省份列）。不认得的照旧返回给前端展示在城市排行里，
     * 但应该计入"未上图"，而不是悄悄消失。
     */
    public static boolean isKnownProvince(String province) {
        String name = toMapName(province);
        return name != null && MAP_PROVINCES.contains(name);
    }
}
