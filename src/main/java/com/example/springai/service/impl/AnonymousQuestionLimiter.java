package com.example.springai.service.impl;

import com.example.springai.common.ErrorCode;
import com.example.springai.exception.BizException;
import com.example.springai.utils.IpUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 未登录用户的提问配额：同一个<b>网段</b>每天最多 N 条。
 *
 * <p><b>只对匿名调用计数</b>，已登录用户不走这里（已登录的走
 * {@link FreeQuestionQuotaLimiter} 或会员不限次）。
 *
 * <p><b>粒度是网段（IPv4 /24、IPv6 /64）而不是完整 IP</b>，和登录风控用同一套判据。
 * 之前用的是完整 IP，看似更精确，实际**拦不住想绕过的人**：
 * <ul>
 *   <li>IPv6 隐私扩展（RFC 4941）会在 /64 <b>内部</b>周期性轮换接口标识 ——
 *       每换一次就是全新的 10 条，而 /64 是稳定的；</li>
 *   <li>IPv4 重拨、手机流量切换也会换地址。</li>
 * </ul>
 * 用户报的"开了无痕就不生效"就是这么来的：新会话拿到新的临时地址 = 新的配额桶。
 *
 * <p>代价是共用出口的人互相挤占额度。对 IPv4 来说额外影响很小 ——
 * 公司出口本来就是一个公网 IP，**完整 IP 粒度下同事们已经在共用了**，
 * /24 只是把邻近的地址也并进来。
 */
@Slf4j
@Component
public class AnonymousQuestionLimiter {

    private static final String KEY_PREFIX = "rag:anon:day:";

    @Autowired
    private DailyCounter dailyCounter;

    @Autowired
    private IpUtils ipUtils;

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
        // 归一成网段再计数。拿不到网段时从严：不计数也不放行，避免绕过
        String prefix = ipUtils.toPrefix(clientIp);
        if (prefix == null) {
            log.warn("无法解析客户端 IP，拒绝匿名提问: {}", clientIp);
            throw new BizException(ErrorCode.FORBIDDEN, "无法识别来源，请登录后使用");
        }

        long used = dailyCounter.increment(KEY_PREFIX + prefix);
        if (used > perIpDailyLimit) {
            log.info("匿名提问超限: 网段={}, used={}/{}", prefix, used, perIpDailyLimit);
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS,
                    "今日免费提问次数已用完（" + perIpDailyLimit + " 条），登录后可继续");
        }
    }

    /**
     * 当日剩余次数。只读，不计数 —— 给前端展示用。
     *
     * @return 剩余条数；未开启或拿不到网段时返回 0
     */
    public long remaining(String clientIp) {
        if (!enabled) {
            return 0;
        }
        String prefix = ipUtils.toPrefix(clientIp);
        if (prefix == null) {
            return 0;
        }
        long used = dailyCounter.current(KEY_PREFIX + prefix);
        return Math.max(0, perIpDailyLimit - used);
    }
}
