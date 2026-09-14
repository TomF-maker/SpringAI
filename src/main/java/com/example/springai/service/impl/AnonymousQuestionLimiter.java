package com.example.springai.service.impl;

import com.example.springai.common.ErrorCode;
import com.example.springai.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 未登录用户的提问配额：同一个 IP 每天最多 N 条。
 *
 * <p>写法完全照 {@code SmsRateLimiter} —— 同一套 Lua 脚本、同一套"TTL 对齐到次日零点"，
 * 这样两个限流器的行为一致，看代码的人不用记两套规则。
 *
 * <p><b>只对匿名调用计数</b>，已登录用户不走这里。否则一个登录用户会把整个办公室
 * 公共出口 IP 的额度用光，其他没登录的同事就都用不了了。
 *
 * <p>粒度是<b>完整 IP</b>，所以换个 IP 就能刷新额度（手机流量切一下就是新的 10 条）。
 * 这是刻意的取舍：目标是挡住随手刷的人，不是挡住有心人。要收紧就把 key 换成
 * {@code IpUtils.toPrefix(ip)}（IPv4 /24），代价是共用出口 IP 的人会互相挤占额度。
 */
@Slf4j
@Component
public class AnonymousQuestionLimiter {

    /** INCR，且只在首次创建时设置过期时间 —— 整个过程在 Redis 内原子完成。 */
    private static final String INCR_WITH_TTL = """
            local c = redis.call('INCR', KEYS[1])
            if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return c
            """;

    private static final String KEY_PREFIX = "rag:anon:day:";

    @Autowired
    private StringRedisTemplate redis;

    @Value("${app.rag.anonymous.enabled:true}")
    private boolean enabled;

    @Value("${app.rag.anonymous.per-ip-daily-limit:10}")
    private long perIpDailyLimit;

    public boolean isEnabled() {
        return enabled;
    }

    public long getPerIpDailyLimit() {
        return perIpDailyLimit;
    }

    /**
     * 记一次匿名提问。超限时抛 {@link BizException}。
     *
     * @param clientIp 由 {@code IpUtils.getClientIp} 归一化后的完整 IP
     */
    public void checkAndRecord(String clientIp) {
        if (!enabled) {
            throw new BizException(ErrorCode.FORBIDDEN, "当前未开放免登录提问，请先登录");
        }
        if (clientIp == null) {
            // 拿不到 IP 时从严：不计数也不放行，避免绕过
            log.warn("无法解析客户端 IP，拒绝匿名提问");
            throw new BizException(ErrorCode.FORBIDDEN, "无法识别来源，请登录后使用");
        }

        long used = incrWithTtl(KEY_PREFIX + clientIp, secondsToNextMidnight());
        if (used > perIpDailyLimit) {
            log.info("匿名提问超限: ip={}, used={}/{}", clientIp, used, perIpDailyLimit);
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS,
                    "今日免费提问次数已用完（" + perIpDailyLimit + " 条），登录后可继续");
        }
    }

    /**
     * 当日剩余次数。只读，不计数 —— 给前端展示用。
     *
     * @return 剩余条数；未开启或拿不到 IP 时返回 0
     */
    public long remaining(String clientIp) {
        if (!enabled || clientIp == null) {
            return 0;
        }
        String value = redis.opsForValue().get(KEY_PREFIX + clientIp);
        long used = 0;
        if (value != null) {
            try {
                used = Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                // 值被人为改坏时按 0 处理，不影响功能
            }
        }
        return Math.max(0, perIpDailyLimit - used);
    }

    private long incrWithTtl(String key, long ttlSeconds) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(INCR_WITH_TTL, Long.class);
        Long value = redis.execute(script, List.of(key), String.valueOf(ttlSeconds));
        return value == null ? 0 : value;
    }

    private long secondsToNextMidnight() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextMidnight = LocalDate.now().plusDays(1).atStartOfDay();
        long seconds = Duration.between(now, nextMidnight).getSeconds();
        return Math.max(seconds, 60);
    }
}
