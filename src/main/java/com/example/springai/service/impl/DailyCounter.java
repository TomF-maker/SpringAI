package com.example.springai.service.impl;

import com.example.springai.common.AppTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 按天重置的 Redis 计数器。
 *
 * <p>抽出来是因为匿名配额、免费额度两处都需要同一套东西，
 * 而项目里本来已经有 {@code SmsRateLimiter} 一份 —— 再不收敛就是第三份复制，
 * TTL 对齐、Lua 脚本、失败兜底迟早各不相同。
 *
 * <p>TTL 用 {@link AppTime#secondsToNextMidnight()} 对齐到<b>北京时间</b>次日零点。
 */
@Slf4j
@Component
public class DailyCounter {

    /** INCR，且只在首次创建时设置过期时间 —— 整个过程在 Redis 内原子完成。 */
    private static final String INCR_WITH_TTL = """
            local c = redis.call('INCR', KEYS[1])
            if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return c
            """;

    @Autowired
    private StringRedisTemplate redis;

    /**
     * 计数 +1。
     *
     * @return 累加后的值；Redis 不可用时返回 0
     */
    public long increment(String key) {
        try {
            DefaultRedisScript<Long> script = new DefaultRedisScript<>(INCR_WITH_TTL, Long.class);
            Long value = redis.execute(script, List.of(key), String.valueOf(AppTime.secondsToNextMidnight()));
            return value == null ? 0 : value;
        } catch (Exception e) {
            // 计数器不可用时不能把请求打死，但也不能静默放行到无限
            log.error("计数器自增失败 key={}: {}", key, e.getMessage());
            return 0;
        }
    }

    /** 当前值（只读，不计数）。键不存在或值被改坏时返回 0。 */
    public long current(String key) {
        try {
            String value = redis.opsForValue().get(key);
            return value == null ? 0 : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        } catch (Exception e) {
            log.error("计数器读取失败 key={}: {}", key, e.getMessage());
            return 0;
        }
    }
}
