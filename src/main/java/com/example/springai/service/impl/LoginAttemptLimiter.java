package com.example.springai.service.impl;

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
 * 登录密码重试限制。
 *
 * <p>此前**完全没有限制** —— 知道用户名就能无限次试密码。企业内网账号通常短且规律，
 * 没有节流的登录接口等于把爆破成本降到零。
 *
 * <p>两层，各拦一种打法：
 * <ul>
 *   <li><b>账号维度</b>：连续失败 {@code account-max} 次就锁 {@code account-lock-seconds}。
 *       拦"盯着一个账号猛试"。</li>
 *   <li><b>IP 维度</b>：同一 IP 在 {@code ip-window-seconds} 内最多尝试 {@code ip-max} 次。
 *       拦"换着用户名继续试"—— 只锁账号的话，攻击者每个用户名试 4 次就换，
 *       永远不触发锁定。</li>
 * </ul>
 *
 * <p><b>账号锁定是有代价的，得知道</b>：攻击者知道某个用户名时，可以故意输错把它锁住，
 * 让对方登录不了（DoS）。缓解手段是锁得短（默认 15 分钟）+ 认证成功后立即清零。
 * 管理员那边还有 {@code PUT /api/users/{id}/unlock-login} 可以手工解锁 —— 但那条路
 * 清的是异地登录状态，**不包含这里的密码锁定**，真被恶意锁了要等 TTL 自然过期。
 *
 * <p><b>用户名必须归一化再当 key</b>：MySQL 的默认排序规则不区分大小写，
 * {@code Admin} 和 {@code admin} 都能登录同一个账号；不归一的话攻击者用大小写变体
 * 就能拿到好几个独立的失败计数桶，5 次的限制形同虚设。
 */
@Slf4j
@Component
public class LoginAttemptLimiter {

    @Autowired
    private StringRedisTemplate redis;

    @Value("${app.login.attempt.account-max:5}")
    private long accountMax;

    @Value("${app.login.attempt.account-lock-seconds:900}")
    private long accountLockSeconds;

    @Value("${app.login.attempt.ip-max:20}")
    private long ipMax;

    @Value("${app.login.attempt.ip-window-seconds:300}")
    private long ipWindowSeconds;

    /**
     * 尝试登录前调用。账号被锁或 IP 尝试过频时抛 {@link BizException}。
     *
     * <p><b>必须在 {@code authenticationManager.authenticate} 之前调</b> ——
     * 放后面就变成"先把密码比完再决定该不该让他试"，那等于没限流。
     */
    public void checkAllowed(String username, String clientIp) {
        String account = normalize(username);
        if (account != null && Boolean.TRUE.equals(redis.hasKey(lockKey(account)))) {
            Long ttl = redis.getExpire(lockKey(account), TimeUnit.SECONDS);
            long minutes = Math.max(1, (ttl == null ? accountLockSeconds : ttl) / 60 + 1);
            log.warn("账号已被锁定，拒绝登录尝试: account={}", account);
            throw new BizException(ErrorCode.FORBIDDEN,
                    "该账号连续登录失败次数过多，请 " + minutes + " 分钟后再试");
        }

        if (clientIp != null && !clientIp.isBlank()) {
            // 每次尝试都计数（成功也算），所以这里只 incr 不判断"失败"
            long used = incrWithTtl("login:attempt:ip:" + clientIp, ipWindowSeconds);
            if (used > ipMax) {
                log.warn("登录尝试过于频繁，拒绝: ip={}, used={}/{}", clientIp, used, ipMax);
                throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "登录尝试过于频繁，请稍后再试");
            }
        }
    }

    /** 密码校验失败时调用。达到阈值就上锁。 */
    public void recordFailure(String username) {
        String account = normalize(username);
        if (account == null) {
            return;
        }
        // 失败计数窗口 = 锁定时长：15 分钟内攒够 5 次才锁，
        // 而不是"历史上总共失败过 5 次"—— 后者会让老用户莫名其妙被锁。
        long failures = incrWithTtl("login:fail:" + account, accountLockSeconds);
        if (failures >= accountMax) {
            redis.opsForValue().set(lockKey(account), "1", accountLockSeconds, TimeUnit.SECONDS);
            log.warn("🔒 账号连续登录失败 {} 次，已锁定 {} 秒: account={}", failures, accountLockSeconds, account);
        }
    }

    /** 登录成功时调用：清零失败计数，别让几次手滑累积到后面把人锁了。 */
    public void recordSuccess(String username) {
        String account = normalize(username);
        if (account != null) {
            redis.delete(List.of("login:fail:" + account, lockKey(account)));
        }
    }

    // ==================== 内部 ====================

    /**
     * 归一化用户名当 key。
     *
     * <p>转小写 + 去空白。**不能省**：MySQL 默认排序规则下 {@code Admin} 能登录
     * {@code admin}，不归一的话同一个账号会有好几个失败计数桶。
     */
    private static String normalize(String username) {
        if (username == null) {
            return null;
        }
        String v = username.trim().toLowerCase();
        return v.isEmpty() ? null : v;
    }

    private static String lockKey(String account) {
        return "login:lock:" + account;
    }

    /** INCR + 首次 EXPIRE，原子完成。复用 {@link RedisScripts} 里那一份，不再抄第四遍。 */
    private long incrWithTtl(String key, long ttlSeconds) {
        Long value = redis.execute(
                new DefaultRedisScript<>(RedisScripts.INCR_WITH_TTL, Long.class),
                List.of(key), String.valueOf(ttlSeconds));
        return value == null ? 0 : value;
    }
}
