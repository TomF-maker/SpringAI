package com.example.springai.service.impl;

import com.example.springai.common.ErrorCode;
import com.example.springai.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 已登录但<b>不是有效会员</b>的用户的每日免费额度。
 *
 * <p>为什么不复用 {@link AnonymousQuestionLimiter}：那个是按 IP 计的，
 * 一个办公室共用出口 IP 会互相挤占；已登录用户有可靠的 userId，按人才对。
 * 而且它 {@code enabled=false} 时的文案是"当前未开放免登录提问，请先登录" ——
 * 对一个<b>已经登录</b>的非会员说这话是胡说八道，所以两套开关、两套文案必须分开。
 */
@Slf4j
@Component
public class FreeQuestionQuotaLimiter {

    private static final String KEY_PREFIX = "rag:free:day:";

    @Autowired
    private DailyCounter dailyCounter;

    @Value("${app.rag.free.per-user-daily-limit:3}")
    private long perUserDailyLimit;

    public long getPerUserDailyLimit() {
        return perUserDailyLimit;
    }

    /**
     * 记一次免费提问。超限时抛 {@link BizException}。
     *
     * @param userId 已登录用户 id
     */
    public void checkAndRecord(Long userId) {
        if (userId == null) {
            // 调用方保证非空；真为 null 就从严，不计数也不放行
            log.warn("免费额度计数缺少 userId，拒绝");
            throw new BizException(ErrorCode.FORBIDDEN, "无法识别用户，请重新登录");
        }

        long used = dailyCounter.increment(KEY_PREFIX + userId);
        if (used > perUserDailyLimit) {
            log.info("非会员免费额度超限: userId={}, used={}/{}", userId, used, perUserDailyLimit);
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS,
                    "今日免费提问次数已用完（" + perUserDailyLimit + " 条），开通会员可不限次提问");
        }
    }

    /** 当日剩余次数。只读，不计数。 */
    public long remaining(Long userId) {
        if (userId == null) {
            return 0;
        }
        long used = dailyCounter.current(KEY_PREFIX + userId);
        return Math.max(0, perUserDailyLimit - used);
    }
}
