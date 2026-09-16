package com.example.springai.service.impl;

import com.example.springai.common.AppTime;
import com.example.springai.common.ErrorCode;
import com.example.springai.common.RedisScripts;
import com.example.springai.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 发邮箱验证码的限流。
 *
 * <p>短信那边早有 {@link SmsRateLimiter}（每条短信都是钱），而邮箱这条路**一直是裸露的** ——
 * 任何人都能拿任意邮箱反复触发发信，既骚扰收件人，也把发信额度刷光。
 * 注册和忘记密码都走这个接口，暴露面比想象中大。
 *
 * <p>三层，和短信那套同构（但不共用代码：短信的额度是钱，邮箱的是骚扰，
 * 两者的阈值该分开调，混在一起以后改一边会误伤另一边）：
 * <ul>
 *   <li><b>邮箱 60 秒冷却</b> —— 用户连点"获取验证码"不会连发；</li>
 *   <li><b>邮箱每天 {@code per-day} 封</b> —— 防盯着一个人骚扰；</li>
 *   <li><b>单 IP 每小时 {@code per-ip-hour} 封</b> —— 防"换邮箱继续刷"。</li>
 * </ul>
 *
 * <p>计数用 Lua 做 INCR + EXPIRE 原子化。分成两条命令的话，中间崩溃会留下一个
 * 没有 TTL 的 key，等于把一个邮箱**永久**封死。
 */
@Slf4j
@Component
public class EmailCodeRateLimiter {

    @Autowired
    private StringRedisTemplate redis;

    @Value("${app.email.rate.cooldown-seconds:60}")
    private long cooldownSeconds;

    @Value("${app.email.rate.per-day:10}")
    private long perDay;

    @Value("${app.email.rate.per-ip-hour:20}")
    private long perIpHour;

    /**
     * 校验并计入一次发送额度。超限时抛 {@link BizException} ——
     * **必须是它而不是裸 RuntimeException**：后者会被兜底处理器映射成 HTTP 500 +
     * INTERNAL_ERROR，把"你发得太快"伪装成服务端故障，既误导用户也会污染错误监控。
     * 按项目约定，业务/校验失败一律是 200 + success:false。
     *
     * @param email    收件邮箱
     * @param clientIp 客户端 IP，可为 null（取不到就只做邮箱维度的限制）
     */
    public void checkAndRecord(String email, String clientIp) {
        String key = normalize(email);
        if (key == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请填写邮箱");
        }

        // 1. 冷却：SETNX 本身就是原子的
        String cdKey = "email:cd:" + key;
        Boolean acquired = redis.opsForValue()
                .setIfAbsent(cdKey, "1", cooldownSeconds, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(acquired)) {
            Long ttl = redis.getExpire(cdKey, TimeUnit.SECONDS);
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS,
                    "发送过于频繁，请 " + Math.max(1, ttl == null ? 1 : ttl) + " 秒后再试");
        }

        // 2. 邮箱当日额度，TTL 对齐到次日零点
        long dayTtl = secondsToNextMidnight();
        if (incrWithTtl("email:day:" + key, dayTtl) > perDay) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "该邮箱今日验证码发送次数已达上限，请明天再试");
        }

        // 3. 单 IP 小时额度
        if (clientIp != null && !clientIp.isBlank()
                && incrWithTtl("email:hour:ip:" + clientIp, 3600) > perIpHour) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "当前网络发送次数过多，请稍后再试");
        }
    }

    /** 发信失败时归还冷却名额，避免用户因为一次 SMTP 抖动被锁 60 秒。 */
    public void releaseCooldown(String email) {
        String key = normalize(email);
        if (key != null) {
            redis.delete("email:cd:" + key);
        }
    }

    /**
     * 归一化后再当 key。
     *
     * <p>**不能省**：邮箱地址大小写不敏感（{@code A@x.com} 和 {@code a@x.com} 是同一个信箱），
     * 不归一的话同一个人会有好几个额度桶，10 封的限制变成 20 封。
     */
    private static String normalize(String email) {
        if (email == null) {
            return null;
        }
        String v = email.trim().toLowerCase();
        return v.isEmpty() ? null : v;
    }

    private long incrWithTtl(String key, long ttlSeconds) {
        Long value = redis.execute(
                new DefaultRedisScript<>(RedisScripts.INCR_WITH_TTL, Long.class),
                List.of(key), String.valueOf(ttlSeconds));
        return value == null ? 0 : value;
    }

    /**
     * 到北京时间次日零点的秒数。
     *
     * <p>刻意用 {@code AppTime} 而不是 {@code LocalDateTime.now()} —— 后者按 JVM 默认时区，
     * 生产 Linux 默认是 UTC，额度会在北京时间早上 8 点重置而不是零点。
     * （顺带一提：{@code SmsRateLimiter.secondsToNextMidnight} 用的就是 JVM 时区，
     * 那是个既有问题，不在本次范围内。）
     */
    private long secondsToNextMidnight() {
        return AppTime.secondsToNextMidnight();
    }
}
