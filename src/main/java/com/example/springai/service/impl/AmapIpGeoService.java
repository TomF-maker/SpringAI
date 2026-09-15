package com.example.springai.service.impl;

import com.example.springai.common.IpGeo;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * 用高德 IP 定位解析归属地。
 *
 * <p>相比 ip-api 免费版的好处是硬的：HTTPS、**中文地名**（{@code 广东省} / {@code 深圳市}）、
 * 国内准确度高、走国内链路（30-80ms）、商用无额外限制。
 *
 * <p>Key 默认复用天气那个（{@code weather.api.key}），同一把高德 key，
 * 不必配两遍；需要单独限权时用 {@code app.geo.amap.key} 覆盖。
 *
 * <p>局限：高德的库只有国内数据，<b>境外 IP 返回空省市</b>。所以 {@code ip_country}
 * 在这一实现下恒为"中国"或 null，做不了"按国家拆分"的报表 —— 那正是 ip-api 的价值所在。
 * 要哪个得先想清楚。
 */
@Service
@ConditionalOnProperty(name = "app.geo.provider", havingValue = "amap")
public class AmapIpGeoService extends AbstractIpGeoService {

    private static final String ENDPOINT = "https://restapi.amap.com/v3/ip";
    private static final String CHINA = "中国";

    /** 最坏 800ms。刻意不用共享的 RestTemplate Bean，理由见 {@link #buildRestTemplate(int, int)}。 */
    private final RestTemplate restTemplate = buildRestTemplate(300, 500);

    @Value("${app.geo.amap.key:${weather.api.key:}}")
    private String key;

    @Override
    protected String providerName() {
        return "amap（中文地名）";
    }

    @Override
    protected IpGeo fetch(String ip) throws Exception {
        String body = restTemplate.getForObject(
                ENDPOINT + "?ip={ip}&key={key}", String.class, ip, key);
        JsonNode node = objectMapper.readTree(body);

        // 高德成功失败都返回 HTTP 200，`status` 在 body 里：0 = 失败（info 里是原因，如 INVALID_USER_KEY）。
        // 同 AliyunSmsService.checkResponse 的坑。
        if (!"1".equals(node.path("status").asText())) {
            throw new IllegalStateException("高德返回失败: " + node.path("info").asText());
        }
        // 高德对查不到的 IP 会返回 "province": []（空数组）或空串，
        // 两种都落到 asTextOrNull 里被归一成 null。
        String province = asTextOrNull(node, "province");
        String city = asTextOrNull(node, "city");

        // 关键：高德对"这个 IP 我库里没有"和"这是个境外 IP"返回**一模一样**的空数组，
        // 两者无法区分。所以这里不能顺手填个 country="中国" —— 那是个没有依据的断言，
        // 会把所有查不到的流量（含境外）都算成国内，而报表上完全看不出来。
        // 宁可返回 UNKNOWN，让它落成 NULL、在 BI 上显示为"未解析"。
        //
        // 实测覆盖不全：114.114.114.114、120.229.90.x 都查不到，
        // 而 14.155.207.1、202.108.22.5、180.101.50.242 能正常解析出中文省市。
        if (province == null && city == null) {
            return IpGeo.UNKNOWN;
        }
        return new IpGeo(CHINA, province, city);
    }

    /** 高德的省市字段可能是字符串、空串或空数组，统一收敛成 null。 */
    private static String asTextOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isTextual() ? v.asText() : null;
    }
}
