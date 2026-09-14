package com.example.springai.common;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 应用时间基准。<b>所有业务时间都从这里取，不要直接用 {@code LocalDateTime.now()}。</b>
 *
 * <p>为什么必须显式指定时区：{@code LocalDateTime.now()} 和
 * {@code @Scheduled} 不写 zone 时都按 <b>JVM 默认时区</b>，而生产是云上 Linux，
 * 默认常常是 UTC。后果有两个，都不显眼但都会咬人：
 * <ul>
 *   <li>定时任务实际在北京时间 08:00 跑，而不是零点；</li>
 *   <li>写进库里的"某个零点"和判定用的 now 差 8 小时 —— 会员能多白嫖 8 小时。</li>
 * </ul>
 *
 * <p>注意 {@code application.yaml} 里的 {@code serverTimezone=Asia/Shanghai}
 * <b>只影响 JDBC 连接</b>，管不到上面这两处，别指望它。
 */
public final class AppTime {

    private AppTime() {
    }

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 业务当前时间。 */
    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }

    /** 距次日零点的秒数，供按天重置的计数器做 TTL。下限 60 秒，避免零点整算出 0 导致 key 立刻过期。 */
    public static long secondsToNextMidnight() {
        LocalDateTime now = now();
        LocalDateTime nextMidnight = LocalDate.now(ZONE).plusDays(1).atStartOfDay();
        return Math.max(Duration.between(now, nextMidnight).getSeconds(), 60);
    }
}
