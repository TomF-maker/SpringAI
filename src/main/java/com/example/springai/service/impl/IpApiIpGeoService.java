package com.example.springai.service.impl;

import com.example.springai.common.IpGeo;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * 用 ip-api.com 解析归属地。
 *
 * <p><b>三个已知约束，用之前先想清楚：</b>
 * <ol>
 *   <li><b>免费版只支持 HTTP</b>（无 HTTPS），用户真实 IP 是明文发给第三方的；</li>
 *   <li><b>免费版明确禁止商用</b> —— 这是公司内部系统，严格说踩线，要上线得买 Pro；</li>
 *   <li><b>返回的是英文地名</b>（{@code Guangdong} / {@code Shenzhen}）——
 *       {@code lang=zh-CN} 是付费功能，这里没传。于是 {@code ip_province} 列会写进英文，
 *       一旦以后换成高德（返回 {@code 广东省}），同一列会中英混杂，
 *       BI 的 GROUP BY 会把它们当成两个省 —— 这比服务商挂掉更隐蔽。
 *       <b>所以选定一个就别来回切</b>，要切就得连同历史数据一起洗。</li>
 * </ol>
 *
 * <p>{@code matchIfMissing = true}：{@code app.geo.provider} 写错值时也得有一个 Bean 兜底。
 * 短信那边踩过反面的坑 —— 一个 {@code @ConditionalOnProperty} 都不匹配时，
 * 注入点直接 {@code NoSuchBeanDefinitionException}，应用起不来。
 */
@Service
@ConditionalOnProperty(name = "app.geo.provider", havingValue = "ipapi", matchIfMissing = true)
public class IpApiIpGeoService extends AbstractIpGeoService {

    private static final String ENDPOINT = "http://ip-api.com/json/";

    /**
     * 最坏 800ms。刻意不用共享的 RestTemplate Bean —— 它是裸的 new RestTemplate()，
     * 超时是 0 = 无限等待。详见 {@link #buildRestTemplate(int, int)}。
     */
    private final RestTemplate restTemplate = buildRestTemplate(300, 500);

    @Override
    protected String providerName() {
        return "ipapi（英文地名，免费版禁商用）";
    }

    @Override
    protected IpGeo fetch(String ip) throws Exception {
        String body = restTemplate.getForObject(
                ENDPOINT + ip + "?fields=status,message,country,regionName,city", String.class);
        JsonNode node = objectMapper.readTree(body);

        // ip-api 失败时也返回 HTTP 200，业务状态在 body 里 —— 必须看 status，不能只看状态码。
        // 同 AliyunSmsService.checkResponse 的坑。
        if (!"success".equals(node.path("status").asText())) {
            throw new IllegalStateException("ip-api 返回失败: " + node.path("message").asText());
        }
        return new IpGeo(
                node.path("country").asText(null),
                node.path("regionName").asText(null),
                node.path("city").asText(null));
    }
}
