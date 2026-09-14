package com.example.springai.service.impl;

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
import java.util.concurrent.TimeUnit;

/**
 * 发短信的限流。每条短信都是钱，这个接口等同于一个计费入口。
 *
 * <p>层级：手机号 60 秒冷却 → 手机号 5 次/小时 → 手机号 10 次/天
 * → 单 IP 20 次/天 → 全局熔断上限。
 *
 * <p>计数用 Lua 脚本做 INCR + EXPIRE 原子化：分成两条命令的话，
 * 中间崩溃会留下一个没有 TTL 的 key，等于把该手机号永久封死。
 */
@Slf4j
@Component
public class SmsRateLimiter {

    /** INCR，且只在首次创建时设置过期时间 —— 整个过程在 Redis 内原子完成。 */
    private static final String INCR_WITH_TTL = """
            local c = redis.call('INCR', KEYS[1])
            if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return c
            """;

    @Autowired
    private StringRedisTemplate redis;

    @Value("${app.sms.rate.cooldown-seconds:60}")
    private long cooldownSeconds;
    @Value("${app.sms.rate.per-hour:5}")
    private long perHour;
    @Value("${app.sms.rate.per-day:10}")
    private long perDay;
    @Value("${app.sms.rate.per-ip-day:20}")
    private long perIpDay;
    @Value("${app.sms.rate.global-day:200}")
    private long globalDay;

    /**
     * 校验并计入一次发送额度。超限时抛出带剩余时间的友好提示。
     *
     * @param phone 已归一化的手机号（务必先归一化，否则同一个人的两种写法会变成两个桶）
     */
    public void checkAndRecord(String phone, String clientIp) {
        // 1. 冷却：SETNX 本身就是原子的
        String cdKey = "sms:cd:" + phone;
        Boolean acquired = redis.opsForValue()
                .setIfAbsent(cdKey, "1", cooldownSeconds, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(acquired)) {
            Long ttl = redis.getExpire(cdKey, TimeUnit.SECONDS);
            throw new RuntimeException("发送过于频繁，请 " + Math.max(1, ttl == null ? 1 : ttl) + " 秒后再试");
        }

        // 2. 小时额度
        if (incrWithTtl("sms:hour:" + phone, 3600) > perHour) {
            throw new RuntimeException("该手机号发送次数过多，请稍后再试");
        }

        // 3. 当日额度（手机号 / IP / 全局），TTL 对齐到次日零点
        long dayTtl = secondsToNextMidnight();
        if (incrWithTtl("sms:day:" + phone, dayTtl) > perDay) {
            throw new RuntimeException("该手机号今日发送次数已达上限");
        }
        if (clientIp != null && incrWithTtl("sms:day:ip:" + clientIp, dayTtl) > perIpDay) {
            throw new RuntimeException("当前网络发送次数已达上限");
        }
        if (incrWithTtl("sms:day:global", dayTtl) > globalDay) {
            log.error("短信全局日额度已用尽（{} 条），已熔断", globalDay);
            throw new RuntimeException("短信服务今日额度已用尽，请明天再试");
        }
    }

    /** 发送失败时归还冷却名额，避免用户因为一次网络错误被锁 60 秒。 */
    public void releaseCooldown(String phone) {
        redis.delete("sms:cd:" + phone);
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
