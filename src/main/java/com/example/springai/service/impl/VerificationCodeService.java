package com.example.springai.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.example.springai.common.RedisScripts;
import com.example.springai.service.VerificationCodeServiceI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 邮箱验证码的存取与校验。
 *
 * <p><b>校验有尝试次数上限。</b>此前这里只有 get + equals：6 位数字、300 秒窗口、
 * 不限次数 —— 脚本在窗口内能试掉十几万次，10^6 的空间有**实际**成功率，
 * 而这条路通向 {@code /api/auth/reset-password}，也就是**可以直接改掉任意已知邮箱的密码**。
 * 给"发验证码"加图形验证码而这里不限次，等于只关了一半的门。
 *
 * <p>上限的语义与 {@code LoginSecurityService} 的挑战一致：同一邮箱连续错
 * {@value #MAX_ATTEMPTS} 次就把验证码销毁，用户得重新获取。计数键的 TTL 与验证码相同 ——
 * 是"这次验证码窗口内错了几次"，不是"历史上总共错过几次"（后者会让老用户莫名被锁）。
 */
@Slf4j
@Service
public class VerificationCodeService implements VerificationCodeServiceI {

    private static final String CODE_PREFIX = "verify:code:";
    private static final String TRY_PREFIX = "verify:try:";
    private static final long CODE_EXPIRE_SECONDS = 300; // 5分钟

    /** 连续错几次销毁验证码。同 {@code LoginSecurityService.MAX_ATTEMPTS}。 */
    private static final int MAX_ATTEMPTS = 5;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 生成并保存验证码
     */
    public String generateAndSave(String email) {
        String code = RandomUtil.randomNumbers(6);
        String key = CODE_PREFIX + email;
        stringRedisTemplate.opsForValue().set(key, code, CODE_EXPIRE_SECONDS, TimeUnit.SECONDS);
        return code;
    }

    /**
     * 验证验证码是否正确。成功即销毁（一次性）。
     *
     * <p>注意失败**不销毁**验证码 —— 手滑输错一位还有机会。但会累计错误次数，
     * 到 {@value #MAX_ATTEMPTS} 次就销毁，堵住"无限次猜"。
     */
    public boolean verify(String email, String code) {
        if (email == null || code == null) {
            return false;
        }
        String key = CODE_PREFIX + email;
        String storedCode = stringRedisTemplate.opsForValue().get(key);
        if (storedCode == null) {
            // 过期、已被成功使用、或者压根没发过。这里**不能**写任何键，
            // 否则拿任意邮箱打这个接口就能刷出无限个计数键。
            return false;
        }
        // 验证通过后删除验证码（一次性使用）
        if (storedCode.equals(code)) {
            stringRedisTemplate.delete(key);
            stringRedisTemplate.delete(TRY_PREFIX + email);
            return true;
        }

        long attempts = incrWithTtl(TRY_PREFIX + email, CODE_EXPIRE_SECONDS);
        if (attempts >= MAX_ATTEMPTS) {
            stringRedisTemplate.delete(key);
            log.warn("🙅 邮箱验证码连续错误次数超限，已销毁（邮箱不记日志）：attempts={}", attempts);
        }
        return false;
    }

    /**
     * INCR 并返回新值，仅首次创建时设 TTL，整个过程在 Redis 内原子完成。
     *
     * <p>不手写 INCR 再 EXPIRE：中间崩了就是一个**永不过期**的计数器，
     * 表现为"这个邮箱再也收不到可用的验证码"，而且不报任何错。
     */
    private long incrWithTtl(String key, long ttlSeconds) {
        Long value = stringRedisTemplate.execute(
                new DefaultRedisScript<>(RedisScripts.INCR_WITH_TTL, Long.class),
                List.of(key), String.valueOf(ttlSeconds));
        return value == null ? 0 : value;
    }
}
