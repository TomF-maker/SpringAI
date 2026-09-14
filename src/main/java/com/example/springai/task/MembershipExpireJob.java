package com.example.springai.task;

import com.example.springai.service.MembershipServiceI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 会员到期清理：每晚 0 点（北京时间）把已过期的非永久会员刷成非会员。
 *
 * <p><b>这个任务不是权限判定的依据，只是数据卫生。</b>
 * 权限看的是请求时的惰性判定 {@code SysUser.hasActiveMembership(now)} ——
 * 上午 10 点过期的用户，从 10:00 起就不能提问了，不等这个任务。
 * 它做的是三件事：让管理页不撒谎、落 EXPIRE 审计、
 * 以及消除 {@code member_type != null} 被下一个人误读成"是会员"的坑。
 *
 * <p>所以：cron 配错、单飞键卡死、应用零点没起来 —— 这些全都无害。
 */
@Slf4j
@Component
public class MembershipExpireJob {

    private static final String LOCK_KEY = "member:expire:job";

    @Autowired
    private MembershipServiceI membershipService;

    @Autowired
    private StringRedisTemplate redis;

    @Value("${app.membership.expire-job.batch-limit:500}")
    private int batchLimit;

    /**
     * <b>必须显式写 {@code zone}</b>：不写就按 JVM 默认时区，而生产是云上 Linux，
     * 默认常常是 UTC —— 任务会在北京时间 08:00 跑而不是零点。
     */
    @Scheduled(cron = "0 0 0 * * ?", zone = "Asia/Shanghai")
    public void expireOverdueMemberships() {
        // 单飞键：现在单实例部署用不上，但多实例时每个实例都会跑一遍、白做功。
        // TTL 10 分钟，且**不在 finally 里删** —— 删掉的话第一个实例跑完，
        // 第二个紧接着又会跑一遍（虽然 CAS 幂等不会出错，但白干）。
        // 任务中途挂了也没关系：TTL 到期后自然恢复。
        Boolean acquired = redis.opsForValue()
                .setIfAbsent(LOCK_KEY, "1", 10, TimeUnit.MINUTES);
        if (!Boolean.TRUE.equals(acquired)) {
            log.info("会员到期清理已在其它实例执行，本次跳过");
            return;
        }

        long start = System.currentTimeMillis();
        try {
            int cleaned = membershipService.expireOverdue(batchLimit);
            long elapsed = System.currentTimeMillis() - start;
            if (cleaned >= batchLimit) {
                log.warn("🧹 会员到期清理处理了 {} 条（已达单次上限 {}），可能还有存量，下次继续",
                        cleaned, batchLimit);
            } else {
                log.info("🧹 会员到期清理完成: {} 条，耗时 {}ms", cleaned, elapsed);
            }
        } catch (Throwable t) {
            // 定时任务里抛异常会静默丢掉（除非配了 ErrorHandler），所以自己兜住并记日志
            log.error("❌ 会员到期清理失败", t);
        }
    }
}
