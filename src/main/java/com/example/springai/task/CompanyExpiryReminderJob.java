package com.example.springai.task;

import com.example.springai.config.CompanyReminderProperties;
import com.example.springai.dto.CompanyReminderResult;
import com.example.springai.mapper.SysCompanyMapper;
import com.example.springai.service.CompanyExpiryReminderServiceI;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 到期提醒：每天早 9 点（北京时间）扫描所有客户公司的合约，命中 30 / 7 天窗口的发提醒。
 *
 * <p>为什么是早上 9 点：这是给人看的邮件 —— 顾问上班就能看到并当天安排沟通。
 * 零点发的话会被压在一堆夜间邮件里，等于没发。
 *
 * <p>失败语义与新闻推送一致（会员清理那边失败无害，这边不是）：
 * <b>发信失败不标记"已提醒"</b>，第二天照常命中并重试 —— 见
 * {@code CompanyExpiryReminderService} 的窗口判定（"≤ 阈值且未发过"）。
 */
@Slf4j
@Component
public class CompanyExpiryReminderJob {

    private static final String LOCK_KEY = "company:expiry:reminder:job";

    @Autowired
    private CompanyExpiryReminderServiceI reminderService;

    @Autowired
    private CompanyReminderProperties props;

    @Autowired
    private SysCompanyMapper companyMapper;

    @Autowired
    private StringRedisTemplate redis;

    /**
     * 启动探针：确认提醒用的两列都在（{@code doc/schema.sql} 的 2026-09-18 B1 段）。
     *
     * <p>缺列时应用照常启动，症状只是"提醒一直不来" —— 那种故障不主动报出来，
     * 要等到有人问才会被发现。
     */
    @PostConstruct
    void verifySchema() {
        try {
            companyMapper.probeReminderColumns();
            log.info("📍 到期提醒列就绪（sys_company.remind_*_sent_at）");
        } catch (Exception e) {
            log.error("❌ 到期提醒不可用：sys_company 缺少 remind_30_sent_at / remind_7_sent_at。"
                    + "请先执行 doc/schema.sql 里 2026-09-18「B1 到期提醒」那条 ALTER: {}", e.getMessage());
        }
    }

    /**
     * <b>必须显式写 {@code zone}</b>：不写就按 JVM 默认时区，生产是云上 Linux、
     * 默认常常是 UTC —— 提醒会在北京时间下午 5 点发出去。
     */
    @Scheduled(cron = "${app.company.expiry-reminder.cron:0 0 9 * * ?}", zone = "Asia/Shanghai")
    public void run() {
        if (!props.isEnabled()) {
            log.debug("到期提醒已关闭（app.company.expiry-reminder.enabled=false）");
            return;
        }

        // 单飞键：现在单实例用不上，多实例时每个实例都会跑一遍、给收件人发多封。
        // TTL 30 分钟且**不在 finally 里删** —— 删掉的话第一个实例发完，第二个紧接着再发一封。
        // 任务中途挂了也没关系，TTL 到期自然恢复（真正的幂等靠库里的 remind_*_sent_at）。
        Boolean acquired = redis.opsForValue()
                .setIfAbsent(LOCK_KEY, "1", 30, TimeUnit.MINUTES);
        if (!Boolean.TRUE.equals(acquired)) {
            log.info("到期提醒已在其它实例执行，本次跳过");
            return;
        }

        try {
            CompanyReminderResult result = reminderService.runDailyReminder();
            if (result.getHits().isEmpty()) {
                return;   // 服务里已经打过日志
            }
            log.info("📅 到期提醒完成: 扫描 {} 家 / 命中 {} 家 / 内部送达 {} 位 / 客户送达 {} 位 / 已标记={}",
                    result.getScanned(), result.getHits().size(),
                    result.getInternalSent(), result.getClientSent(), result.isMarked());
        } catch (Throwable t) {
            // 定时任务里抛异常会被静默丢掉（除非配了 ErrorHandler），所以自己兜住并记日志
            log.error("❌ 到期提醒任务失败", t);
        }
    }
}
