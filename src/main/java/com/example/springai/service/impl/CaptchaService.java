package com.example.springai.service.impl;

import cn.hutool.captcha.LineCaptcha;
import cn.hutool.captcha.generator.RandomGenerator;
import com.example.springai.common.ErrorCode;
import com.example.springai.common.RedisScripts;
import com.example.springai.dto.CaptchaResponse;
import com.example.springai.exception.BizException;
import com.example.springai.service.CaptchaServiceI;
import com.example.springai.utils.IpUtils;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 图形验证码的签发与校验。
 *
 * <p>存在的理由：{@code /api/auth/send-code}（邮箱）和 {@code /api/auth/send-sms-code}（短信）
 * 此前对"谁在调用"没有任何要求 —— 拿 Postman 直接 POST 就能给**任意**邮箱/手机号触发发信。
 * 已有的两个限流器只管"频率"，脚本把频率压到阈值以下就能长期刷。
 *
 * <p><b>它的定位是加一道人力成本，不是防刷主体。</b>兜底的是
 * {@code EmailCodeRateLimiter} / {@code SmsRateLimiter}。别为了"更安全"把图调得更难认。
 *
 * <h3>四条不能改坏的规则</h3>
 *
 * <ol>
 *   <li><b>消费必须原子。</b>比对通过后用 {@code SETNX} 抢唯一一次消费权（同
 *       {@code LoginSecurityService} 的 {@code USED_PREFIX}）。只用 {@code get} + {@code delete}
 *       的话，中间不原子 —— 两个并发请求带同一个正确 code 会**都**返回 true，
 *       攻击者用 50 个并发 + 同一个解出来的码就能换 50 次发信。
 *       也**不要用 {@code GETDEL}**：它不匹配时也会删，第一次猜错就把码毁掉，
 *       5 次尝试上限直接退化成 1 次。</li>
 *   <li><b>未知 id 不写任何 key。</b>取不到答案就立刻返回，绝不能走到尝试计数那一步 ——
 *       否则随机 captchaId 每次都会留下一个带 TTL 的 {@code captcha:try:} 键，
 *       这是一个**内存放大 DoS**（Redis 和 Ollama 挤在同一台 7.6G 无 swap 的机器上）。
 *       同理 id 要先验形状，不然一个 10MB 的 captchaId 就是一次 10MB 的键写入。</li>
 *   <li><b>尝试次数必须有上限。</b>4 位码配 5 次是 0.0005% 命中率；没有上限的话它就是个摆设。</li>
 *   <li><b>签发也要限流。</b>每次签发都要画图 + PNG 编码，不限量就是给 2 核机器递刀子。
 *       网段维度的键名刻意叫 {@code net} 不叫 {@code ip} —— 用完整 IP 的话，
 *       IPv6 隐私扩展（RFC 4941）在 /64 内轮换接口标识即可绕过（同
 *       {@code AnonymousQuestionLimiter} 的取舍）。</li>
 * </ol>
 *
 * <p>key 一览（与既有的 {@code verify:code:} / {@code email:cd:} / {@code sms:cd:} 并列，无冲突）：
 * <pre>
 *   captcha:code:&lt;id&gt;         答案，TTL expire-seconds
 *   captcha:try:&lt;id&gt;          错误尝试计数
 *   captcha:used:&lt;id&gt;         消费单飞标记
 *   captcha:issue:hour:ip:&lt;完整IP&gt;
 *   captcha:issue:hour:net:&lt;网段&gt;
 *   captcha:issue:hour:global
 * </pre>
 */
@Slf4j
@Service
public class CaptchaService implements CaptchaServiceI {

    private static final String CODE_PREFIX = "captcha:code:";
    private static final String TRY_PREFIX = "captcha:try:";
    private static final String USED_PREFIX = "captcha:used:";
    private static final String ISSUE_IP_PREFIX = "captcha:issue:hour:ip:";
    private static final String ISSUE_NET_PREFIX = "captcha:issue:hour:net:";
    private static final String ISSUE_GLOBAL_KEY = "captcha:issue:hour:global";

    private static final int WIDTH = 130;
    private static final int HEIGHT = 48;
    private static final int CODE_LENGTH = 4;

    /**
     * 干扰线数量。
     *
     * <p>hutool 的默认值是 **150**（{@code new LineCaptcha(w, h)} → {@code (w, h, 5, 150)}，
     * 反编译确认），画在 130×48 上人眼根本认不出。20 条足够挡 OCR，又还看得清。
     */
    private static final int INTERFERE_COUNT = 20;

    /**
     * 字符集：去掉了 I O L 0 1 和 u 系列易混字符，只留大写。
     *
     * <p>32 个字符 × 4 位 = 104 万种组合，配 5 次尝试上限足够。
     * 只有大写也意味着**不存在大小写歧义**这一整类问题。
     */
    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

    /** captchaId 是我们自己签发的 UUID，形状必须校验后再拼进 Redis 键。 */
    private static final Pattern ID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private IpUtils ipUtils;

    @Value("${app.captcha.expire-seconds:300}")
    private long expireSeconds;

    @Value("${app.captcha.max-attempts:5}")
    private long maxAttempts;

    @Value("${app.captcha.issue.per-ip-hour:30}")
    private long perIpHour;

    @Value("${app.captcha.issue.per-net-hour:300}")
    private long perNetHour;

    @Value("${app.captcha.issue.global-hour:2000}")
    private long globalHour;

    /**
     * 启动探针：真的画一张图，画完就扔。
     *
     * <p>这一条同时覆盖三个失败面 —— 字体、headless、PNG 编码。它们都**不会**在
     * 应用启动时暴露：没人的时候一切正常，第一个来注册的人才会撞上 500。
     * 尤其要注意字体缺失抛的是 {@code java.lang.Error: Probable fatal error: No fonts found.}
     * （注意是 **Error 不是 Exception**），所以这里必须 {@code catch (Throwable)}。
     */
    @PostConstruct
    void probeRendering() {
        // ImageIO 默认对 OutputStream 启用缓存，会走 FileCacheImageOutputStream ——
        // 也就是每生成一张验证码都在 java.io.tmpdir 建一个临时文件再删掉。
        // 关掉缓存换成纯内存实现，省掉这笔白白的磁盘 IO（/tmp 满时的报错极难查）。
        ImageIO.setUseCache(false);
        try {
            Rendered r = render();
            log.info("🖼️ 图形验证码就绪 ({}x{}, {} 位, 干扰线 {} 条, PNG {} bytes, headless={})",
                    WIDTH, HEIGHT, CODE_LENGTH, INTERFERE_COUNT,
                    r.imageBytes().length, System.getProperty("java.awt.headless"));
        } catch (Throwable t) {
            // 红字：说明 /api/auth/send-code 与 /api/auth/send-sms-code 会全部失败
            log.error("❌ 图形验证码无法生成（字体缺失 / headless / ImageIO 三者之一），"
                    + "两个发码接口会全部失败: {}", t.toString());
        }
    }

    @Override
    public CaptchaResponse issue(String clientIp) {
        checkIssueQuota(clientIp);

        Rendered rendered = render();
        String captchaId = UUID.randomUUID().toString();
        redis.opsForValue().set(CODE_PREFIX + captchaId, rendered.code(),
                expireSeconds, TimeUnit.SECONDS);

        CaptchaResponse response = new CaptchaResponse();
        response.setCaptchaId(captchaId);
        response.setImage(rendered.imageDataUri());
        return response;
    }

    @Override
    public boolean verify(String captchaId, String code) {
        // 形状不对 / 参数缺失一律判否（fail closed）。注意这里连 get 都不做。
        if (captchaId == null || !ID_PATTERN.matcher(captchaId).matches()
                || code == null || code.isBlank()) {
            return false;
        }

        String codeKey = CODE_PREFIX + captchaId;
        String stored = redis.opsForValue().get(codeKey);
        if (stored == null) {
            // 过期、已被成功消费、或者压根没签发过。
            // **这里绝不能写任何 key**（见类注释第 2 条）。
            return false;
        }

        if (!stored.equals(normalize(code))) {
            long attempts = incrWithTtl(TRY_PREFIX + captchaId, expireSeconds);
            if (attempts >= maxAttempts) {
                redis.delete(codeKey);
                log.warn("🙅 图形验证码尝试次数超限，已销毁: id={}", captchaId);
            }
            return false;
        }

        // 比对通过：抢唯一一次消费权。
        // 抢不到说明这个码已经被别的并发请求用掉了 —— 此时必须返回 false，
        // 否则一个解出来的码能换 N 次发信。
        Boolean first = redis.opsForValue()
                .setIfAbsent(USED_PREFIX + captchaId, "1", expireSeconds, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(first)) {
            log.warn("🙅 图形验证码被重复提交（并发重放），已拒绝: id={}", captchaId);
            return false;
        }

        redis.delete(codeKey);
        redis.delete(TRY_PREFIX + captchaId);
        return true;
    }

    // ==================== 内部方法 ====================

    /** 画好的验证码：答案 + 完整 data URI。**不入日志。** */
    private record Rendered(String code, String imageDataUri, byte[] imageBytes) {
    }

    /**
     * 画一张新图。**不落 Redis** —— 启动探针也走这里，探针不该在库里留下键。
     *
     * <p>必须用**带 {@code CodeGenerator} 的构造器**显式传字符集和位数：
     * {@code new LineCaptcha(w, h)} 会用默认的 5 位 + 150 条干扰线，
     * 而"先 new 再 setGenerator"也不行 —— 构造时就已用默认生成器出好码了。
     */
    private Rendered render() {
        LineCaptcha captcha = new LineCaptcha(WIDTH, HEIGHT,
                new RandomGenerator(ALPHABET, CODE_LENGTH), INTERFERE_COUNT);
        return new Rendered(captcha.getCode(), captcha.getImageBase64Data(), captcha.getImageBytes());
    }

    /**
     * 把用户输入归一化后与答案精确比较。
     *
     * <p>NFKC 是给中文输入法的：全角数字（{@code ４}）在视觉上和 {@code 4} 一样，
     * 不归一的话用户会一直看到"验证码错误"却怎么都输不对。
     *
     * <p>{@code Locale.ROOT} 不是多余的：裸 {@code toUpperCase()} 走 JVM 默认 locale，
     * 在土耳其语环境（tr_TR）里 {@code i} 会折成 {@code İ}，含 i 的验证码永远对不上，
     * 而且**不报任何错**。
     */
    private static String normalize(String code) {
        return Normalizer.normalize(code, Normalizer.Form.NFKC)
                .trim()
                .toUpperCase(Locale.ROOT);
    }

    /**
     * 签发限流：完整 IP → 网段 → 全局熔断。
     *
     * <p>三层各有各的用处：完整 IP 保公平、网段抗 IPv6 轮换、全局兜住"打一枪换一个地方"。
     * 全部超限都抛 {@link BizException} —— 抛裸 {@code RuntimeException} 会被兜底处理器
     * 映射成 HTTP 500，把"你刷得太快"伪装成服务端故障。
     */
    private void checkIssueQuota(String clientIp) {
        if (clientIp != null && !clientIp.isBlank()) {
            if (incrWithTtl(ISSUE_IP_PREFIX + clientIp, 3600) > perIpHour) {
                throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "验证码获取过于频繁，请稍后再试");
            }
            String net = ipUtils.toPrefix(clientIp);
            if (net != null && incrWithTtl(ISSUE_NET_PREFIX + net, 3600) > perNetHour) {
                throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "当前网络获取验证码次数过多，请稍后再试");
            }
        }
        if (incrWithTtl(ISSUE_GLOBAL_KEY, 3600) > globalHour) {
            log.error("图形验证码全局小时额度已用尽（{} 次），已熔断", globalHour);
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "服务繁忙，请稍后再试");
        }
    }

    /**
     * INCR 并返回新值，仅首次创建时设 TTL，整个过程在 Redis 内原子完成。
     *
     * <p>不手写 INCR 再 EXPIRE：中间崩了就是一个**永不过期**的计数器，
     * 表现为"这个 IP 的额度再也不恢复"，而且没有任何报错。
     */
    private long incrWithTtl(String key, long ttlSeconds) {
        Long value = redis.execute(
                new DefaultRedisScript<>(RedisScripts.INCR_WITH_TTL, Long.class),
                List.of(key), String.valueOf(ttlSeconds));
        return value == null ? 0 : value;
    }
}
