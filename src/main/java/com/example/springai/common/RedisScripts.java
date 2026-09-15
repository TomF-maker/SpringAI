package com.example.springai.common;

/**
 * 共享的 Redis Lua 脚本。
 *
 * <p>存在的理由：{@code INCR + 首次 EXPIRE} 这段逻辑在 {@code SmsRateLimiter} 和
 * {@code DailyCounter} 里已经各有一份，{@code DailyCounter} 的类注释当时就写了
 * "再不收敛就是第三份复制，TTL 对齐、Lua 脚本、失败兜底迟早各不相同"。
 * IP 归属地的分钟级限流是第三个用它的地方，所以先收敛再使用。
 *
 * <p>脚本必须留在 Lua 里而不是拆成两次 Java 调用：{@code INCR} 和 {@code EXPIRE}
 * 之间只要进程挂掉或连接断开，key 就会变成永不过期的计数器 —— 一个只涨不降的配额，
 * 而且是**静默**的，要等到有人抱怨"额度怎么不恢复了"才会被发现。
 */
public final class RedisScripts {

    private RedisScripts() {
    }

    /**
     * 自增并返回新值；仅在 key 首次创建时设置过期时间。
     *
     * <p>KEYS[1] = 计数器键，ARGV[1] = TTL 秒数。
     */
    public static final String INCR_WITH_TTL = """
            local c = redis.call('INCR', KEYS[1])
            if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return c
            """;
}
