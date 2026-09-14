package com.example.springai.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 阿里云短信通道（RPC 风格签名，HMAC-SHA1 + Base64）。
 *
 * <p><b>注意阿里云的签名算法和腾讯云完全不同</b>，不要互相参照：
 * 腾讯云是 TC3-HMAC-SHA256（签名放在 Authorization 头、签名密钥是 HMAC 链），
 * 阿里云是老的 RPC 风格 —— 把所有参数按 key 排序拼成规范查询串，
 * 再用 {@code HMAC-SHA1(AccessKeySecret + "&", StringToSign)} 取 Base64。
 *
 * <p>几处容易踩的坑：
 * <ul>
 *   <li><b>percentEncode 不是标准 URLEncoder</b>：Java 的 {@code URLEncoder} 把空格编成
 *       {@code +}、把 {@code ~} 编成 {@code %7E}、却把 {@code *} 原样保留，
 *       这三处都必须手工纠正，否则签名与请求串不一致，报
 *       {@code SignatureDoesNotMatch}。</li>
 *   <li><b>签名密钥带一个结尾的 &amp;</b>：是 {@code AccessKeySecret + "&"}，
 *       不是裸的 AccessKeySecret。</li>
 *   <li><b>StringToSign 里的路径要再编码一次</b>：{@code "/"} 编成 {@code "%2F"}。</li>
 *   <li>时间戳是 UTC 的 ISO8601（{@code yyyy-MM-dd'T'HH:mm:ss'Z'}），
 *       与服务器时间相差超过 15 分钟会直接报错。</li>
 * </ul>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.sms.provider", havingValue = "aliyun")
public class AliyunSmsService extends AbstractSmsService {

    private static final String ENDPOINT = "dysmsapi.aliyuncs.com";
    private static final String ACTION = "SendSms";
    /** 短信产品的 API 版本，固定值。 */
    private static final String API_VERSION = "2017-05-25";
    private static final String SIGNATURE_METHOD = "HMAC-SHA1";
    private static final String SIGNATURE_VERSION = "1.0";
    private static final String RESPONSE_FORMAT = "JSON";
    private static final String SUCCESS_CODE = "OK";

    @Value("${app.sms.aliyun.access-key-id:}")
    private String accessKeyId;
    @Value("${app.sms.aliyun.access-key-secret:}")
    private String accessKeySecret;
    @Value("${app.sms.aliyun.sign-name:}")
    private String signName;
    @Value("${app.sms.aliyun.template-code:}")
    private String templateCode;
    @Value("${app.sms.aliyun.region-id:cn-hangzhou}")
    private String regionId;
    @Value("${app.sms.expire-minutes:5}")
    private String expireMinutes;

    @Autowired
    private RestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void deliver(String phone, String code, String scene) {
        if (accessKeyId.isBlank() || accessKeySecret.isBlank()) {
            throw new RuntimeException(
                    "阿里云短信未配置：请设置 app.sms.aliyun.access-key-id / access-key-secret");
        }
        try {
            Map<String, String> params = buildParams(phone, code);
            String canonicalQuery = canonicalQuery(params);
            String signature = sign(canonicalQuery, accessKeySecret);

            String url = "https://" + ENDPOINT + "/?Signature=" + percentEncode(signature)
                    + "&" + canonicalQuery;

            // 用 URI.create 传已经编码好的完整地址：不要再走 URI 模板，
            // 否则 Spring 会对 %xx 二次编码，签名立刻对不上。
            ResponseEntity<String> response = restTemplate.exchange(
                    URI.create(url), HttpMethod.GET, null, String.class);
            checkResponse(response.getBody());
            log.info("阿里云短信已提交，模板 {}，号码 {}", templateCode, phone);
        } catch (RuntimeException e) {
            // 发送失败就把验证码撤掉，避免"短信没出去但验证码还留着"的状态
            clearCode(phone, scene);
            throw e;
        } catch (Exception e) {
            clearCode(phone, scene);
            throw new RuntimeException("阿里云短信发送失败: " + e.getMessage(), e);
        }
    }

    /** 组装全部请求参数（公共参数 + 业务参数），TreeMap 天然按 key 升序。 */
    private Map<String, String> buildParams(String phone, String code) {
        Map<String, String> params = new TreeMap<>();
        // 公共参数
        params.put("AccessKeyId", accessKeyId);
        params.put("Action", ACTION);
        params.put("Format", RESPONSE_FORMAT);
        params.put("RegionId", regionId);
        params.put("SignatureMethod", SIGNATURE_METHOD);
        params.put("SignatureNonce", UUID.randomUUID().toString());
        params.put("SignatureVersion", SIGNATURE_VERSION);
        params.put("Timestamp", utcTimestamp());
        params.put("Version", API_VERSION);
        // 业务参数
        params.put("PhoneNumbers", phone);
        params.put("SignName", signName);
        params.put("TemplateCode", templateCode);
        params.put("TemplateParam", buildTemplateParam(code));
        return params;
    }

    /**
     * 模板变量。参数名必须与阿里云后台审核通过的模板文案里的 ${} 占位符一致，
     * 这里对应「您的验证码为 ${code}，${time} 分钟内有效」这类模板。
     */
    private String buildTemplateParam(String code) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("code", code);
            node.put("time", expireMinutes);
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException("构造短信模板参数失败: " + e.getMessage(), e);
        }
    }

    /** 阿里云成功失败都返回 HTTP 200，必须看 body 里的 Code。 */
    private void checkResponse(String body) {
        if (body == null || body.isBlank()) {
            throw new RuntimeException("阿里云短信返回为空");
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            String code = root.path("Code").asText();
            if (!SUCCESS_CODE.equals(code)) {
                throw new RuntimeException("阿里云短信发送失败: " + code
                        + " - " + root.path("Message").asText());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("阿里云短信响应解析失败: " + e.getMessage(), e);
        }
    }

    // ==================== 签名 ====================

    /**
     * 构造规范查询串：按 key 的字典序升序，对 key 和 value 分别做 percentEncode。
     */
    static String canonicalQuery(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<>(params).entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(percentEncode(entry.getKey()))
                    .append('=')
                    .append(percentEncode(entry.getValue()));
        }
        return sb.toString();
    }

    /**
     * 待签名字符串：{@code GET&%2F&<编码后的规范查询串>}
     */
    static String stringToSign(String canonicalQuery) {
        return "GET" + "&" + percentEncode("/") + "&" + percentEncode(canonicalQuery);
    }

    /**
     * 计算签名。
     *
     * <p>密钥是 {@code AccessKeySecret + "&"}（结尾确实有一个 &amp;），
     * 漏掉它同样只会得到 SignatureDoesNotMatch。
     */
    static String sign(String canonicalQuery, String accessKeySecret) throws GeneralSecurityException {
        String toSign = stringToSign(canonicalQuery);
        byte[] key = (accessKeySecret + "&").getBytes(StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key, "HmacSHA1"));
        byte[] raw = mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(raw);
    }

    /**
     * 阿里云要求的 percentEncode：在 {@link URLEncoder} 的基础上纠正三处差异。
     */
    static String percentEncode(String value) {
        String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8);
        return encoded
                .replace("+", "%20")    // URLEncoder 把空格编成 +，阿里云要求 %20
                .replace("*", "%2A")    // URLEncoder 不编码 *，阿里云要求编码
                .replace("%7E", "~");   // URLEncoder 把 ~ 编成 %7E，阿里云要求保留原字符
    }

    static String utcTimestamp() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
    }

    // ==================== 单元测试入口 ====================

    /** 复现一次完整的签名，供测试与独立实现比对。 */
    static String signForTest(Map<String, String> params, String accessKeySecret)
            throws GeneralSecurityException {
        return sign(canonicalQuery(params), accessKeySecret);
    }
}
