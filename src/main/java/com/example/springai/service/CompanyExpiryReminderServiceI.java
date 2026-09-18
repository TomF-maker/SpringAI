package com.example.springai.service;

import com.example.springai.dto.CompanyReminderResult;

/**
 * 合约到期提醒（提前 30 / 7 天）。
 *
 * <p>为什么需要它：不提醒的话，我们会在**客户被停用当天**才发现 —— 那时客户已经用不了，
 * 电话打过来是问责而不是续费。提前 30 天让续费有充足的沟通时间，7 天是最后一次提醒。
 */
public interface CompanyExpiryReminderServiceI {

    /**
     * 跑一轮提醒：扫描所有"正常 + 有到期日"的公司，命中窗口的发提醒邮件并打标记。
     *
     * <p><b>幂等</b>：同一家公司同一档只发一次（靠 {@code remind_30_sent_at} /
     * {@code remind_7_sent_at}）。重复调用安全 —— 定时任务每天跑、失败重试都靠它。
     */
    CompanyReminderResult runDailyReminder();
}
