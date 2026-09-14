package com.example.springai.service.impl;

import com.example.springai.common.ErrorCode;
import com.example.springai.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 未登录用户的提问配额：同一个 IP 每天最多 N 条。
 *
 * <p><b>只对匿名调用计数</b>，已登录用户不走这里（已登录的走
 * {@link FreeQuestionQuotaLimiter} 或会员不限次）。
 *
 * <p>粒度是<b>完整 IP</b>，所以换个 IP 就能刷新额度（手机流量切一下就是新的 10 条）。
 * 这是刻意的取舍：目标是挡住随手刷的人，不是挡住有心人。要收紧就把 key 换成
 * {@code IpUtils.toPrefix(ip)}（IPv4 /24），代价是共用出口 IP 的人会互相挤占额度。
 */
@Slf4j
@Component
public class AnonymousQuestionLimiter {

    private static final String KEY_PREFIX = "rag:anon:day:";

    @Autowired
    private DailyCounter dailyCounter;

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

        long used = dailyCounter.increment(KEY_PREFIX + clientIp);
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
        long used = dailyCounter.current(KEY_PREFIX + clientIp);
        return Math.max(0, perIpDailyLimit - used);
    }
}
