package com.example.springai.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * 腾讯云短信通道（TC3-HMAC-SHA256 手写签名，不引入官方 SDK）。
 *
 * <p>几处容易踩的坑，都已在实现里规避：
 * <ul>
 *   <li><b>签的必须是实际发出的字节</b>：JSON 只序列化一次，哈希这个字符串并按字节发出。
 *       如果把 POJO 交给消息转换器，key 顺序/空格和哈希的输入可能不一致，直接签名失败。</li>
 *   <li><b>Credential 里是 UTC 日期</b>，不是本地日期。用本地日期的话，
 *       东八区每天 00:00-08:00 这 8 小时会稳定签名失败（典型的"昨天还好好的"）。</li>
 *   <li><b>CanonicalHeaders 每个头都要以 \n 结尾</b>（含最后一个），
 *       拼 CanonicalRequest 时再补一个 \n，所以最后会出现连续两个换行。</li>
 *   <li><b>腾讯云 API 出错也返回 HTTP 200</b>，必须解析 body 里的 Error 与 SendStatusSet。</li>
 * </ul>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.sms.provider", havingValue = "tencent")
public class TencentSmsService extends AbstractSmsService {

    private static final String HOST = "sms.tencentcloudapi.com";
    private static final String SERVICE = "sms";
    private static final String ACTION = "SendSms";
    /** 注意：网上大量博客写的 2019-07-11 是旧版，SendSms 当前版本是 2021-01-11。 */
    private static final String VERSION = "2021-01-11";
    private static final String CONTENT_TYPE = "application/json; charset=utf-8";
    private static final String ALGORITHM = "TC3-HMAC-SHA256";

    @Value("${app.sms.tencent.secret-id:}")
    private String secretId;
    @Value("${app.sms.tencent.secret-key:}")
    private String secretKey;
    @Value("${app.sms.tencent.sdk-app-id:}")
    private String sdkAppId;
    @Value("${app.sms.tencent.sign-name:}")
    private String signName;
    @Value("${app.sms.tencent.template-id:}")
    private String templateId;
    @Value("${app.sms.tencent.region:ap-guangzhou}")
    private String region;
    @Value("${app.sms.expire-minutes:5}")
    private String expireMinutes;

    @Autowired
    private RestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void deliver(String phone, String code, String scene) {
        if (secretId.isBlank() || secretKey.isBlank()) {
            throw new RuntimeException(
                    "腾讯云短信未配置：请设置 app.sms.tencent.secret-id / secret-key");
        }

        try {
            String payload = buildPayload(phone, code);
            ResponseEntity<String> response = doPost(payload);
            checkResponse(response.getBody());
            log.info("腾讯云短信已提交，模板 {}，号码 {}", templateId, phone);
        } catch (RuntimeException e) {
            // 发送失败就把验证码撤掉，避免"短信没出去但验证码还留着"的状态
            clearCode(phone, scene);
            throw e;
        }
    }

    private String buildPayload(String phone, String code) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            ArrayNode numbers = body.putArray("PhoneNumberSet");
            numbers.add("+86" + phone);
            body.put("SmsSdkAppId", sdkAppId);
            body.put("SignName", signName);
            body.put("TemplateId", templateId);
            ArrayNode params = body.putArray("TemplateParamSet");
            params.add(code);
            params.add(expireMinutes);
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("构造短信请求体失败: " + e.getMessage(), e);
        }
    }

    private ResponseEntity<String> doPost(String payload) {
        try {
            long timestamp = Instant.now().getEpochSecond();
            String date = utcDateOf(timestamp);

            String authorization = buildAuthorization(payload, timestamp, date);

            HttpHeaders headers = new HttpHeaders();
            // 必须和参与签名的 content-type 完全一致（包括 charset）
            headers.setContentType(MediaType.parseMediaType(CONTENT_TYPE));
            headers.set("Authorization", authorization);
            headers.set("X-TC-Action", ACTION);
            headers.set("X-TC-Version", VERSION);
            headers.set("X-TC-Timestamp", String.valueOf(timestamp));
            headers.set("X-TC-Region", region);

            // 直接发字符串，保证发出的字节与签名的字节完全相同
            return restTemplate.postForEntity(
                    "https://" + HOST, new HttpEntity<>(payload, headers), String.class);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("腾讯云短信签名失败: " + e.getMessage(), e);
        }
    }

    private String buildAuthorization(String payload, long timestamp, String date)
            throws GeneralSecurityException {

        String canonicalHeaders = "content-type:" + CONTENT_TYPE + "\n"
                + "host:" + HOST + "\n"
                + "x-tc-action:" + ACTION.toLowerCase() + "\n";
        String signedHeaders = "content-type;host;x-tc-action";

        String canonicalRequest = "POST" + "\n"
                + "/" + "\n"
                + "" + "\n"                      // POST 的 CanonicalQueryString 为空
                + canonicalHeaders + "\n"        // canonicalHeaders 本身已以 \n 结尾
                + signedHeaders + "\n"
                + sha256Hex(payload);

        String credentialScope = date + "/" + SERVICE + "/tc3_request";
        String stringToSign = ALGORITHM + "\n"
                + timestamp + "\n"
                + credentialScope + "\n"
                + sha256Hex(canonicalRequest);

        // 第一步的 key 是字面量 "TC3" + SecretKey；之后每一步的 key 都是上一步的**原始字节**，
        // 不是它的十六进制字符串 —— 混淆这两者是另一个常见错误。
        byte[] secretDate = hmacSha256(("TC3" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] secretService = hmacSha256(secretDate, SERVICE);
        byte[] secretSigning = hmacSha256(secretService, "tc3_request");
        String signature = HexFormat.of().formatHex(hmacSha256(secretSigning, stringToSign));

        return ALGORITHM + " Credential=" + secretId + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;
    }

    /** 腾讯云成功失败都返回 HTTP 200，必须看 body。 */
    private void checkResponse(String body) {
        if (body == null || body.isBlank()) {
            throw new RuntimeException("腾讯云短信返回为空");
        }
        try {
            JsonNode response = objectMapper.readTree(body).path("Response");
            JsonNode error = response.path("Error");
            if (!error.isMissingNode() && !error.isNull()) {
                throw new RuntimeException("腾讯云短信失败: "
                        + error.path("Code").asText() + " - " + error.path("Message").asText());
            }
            JsonNode statusSet = response.path("SendStatusSet");
            if (!statusSet.isArray() || statusSet.isEmpty()) {
                throw new RuntimeException("腾讯云短信未返回发送状态");
            }
            JsonNode first = statusSet.get(0);
            String statusCode = first.path("Code").asText();
            if (!"Ok".equals(statusCode)) {
                throw new RuntimeException("腾讯云短信发送失败: " + statusCode
                        + " - " + first.path("Message").asText());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("腾讯云短信响应解析失败: " + e.getMessage(), e);
        }
    }

    private static String sha256Hex(String data) throws GeneralSecurityException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] hmacSha256(byte[] key, String data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 由时间戳推导出签名用的日期。
     *
     * <p><b>必须用 UTC</b>。用本地时区（东八区）的话，每天 00:00-08:00 这 8 小时里
     * 算出来的日期会比 UTC 早一天，签名稳定失败 —— 典型的"昨天还好好的，今天早上就不行了"。
     */
    static String utcDateOf(long timestamp) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd")
                .withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochSecond(timestamp));
    }

    // ==================== 以下为单元测试入口 ====================

    /** 复现一次完整的 Authorization 生成。 */
    static String signForTest(String payload, long timestamp, String utcDate,
                              String secretId, String secretKey) throws GeneralSecurityException {
        TencentSmsService svc = new TencentSmsService();
        svc.secretId = secretId;
        svc.secretKey = secretKey;
        return svc.buildAuthorization(payload, timestamp, utcDate);
    }

    /**
     * 暴露签名的中间产物供测试断言。
     *
     * <p>TC3 签名最常见的错误不是算法错，而是**拼接结构错**（少一个换行、
     * 大小写不对、顺序不对），而这些都能在 CanonicalRequest / StringToSign
     * 这两个字符串上直接看出来。所以测试锁的是这两串的字面值。
     *
     * @return [canonicalRequest, stringToSign, signedHeaders]
     */
    static String[] canonicalPartsForTest(String payload, long timestamp, String utcDate)
            throws GeneralSecurityException {
        String canonicalHeaders = "content-type:" + CONTENT_TYPE + "\n"
                + "host:" + HOST + "\n"
                + "x-tc-action:" + ACTION.toLowerCase() + "\n";
        String signedHeaders = "content-type;host;x-tc-action";
        String canonicalRequest = "POST" + "\n"
                + "/" + "\n"
                + "" + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + sha256Hex(payload);
        String credentialScope = utcDate + "/" + SERVICE + "/tc3_request";
        String stringToSign = ALGORITHM + "\n"
                + timestamp + "\n"
                + credentialScope + "\n"
                + sha256Hex(canonicalRequest);
        return new String[]{canonicalRequest, stringToSign, signedHeaders};
    }
}
