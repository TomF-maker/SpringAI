package com.example.springai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.springai.common.AppTime;
import com.example.springai.common.EmailFormat;
import com.example.springai.config.CompanyReminderProperties;
import com.example.springai.dto.CompanyReminderResult;
import com.example.springai.entity.SysCompany;
import com.example.springai.entity.SysUser;
import com.example.springai.mapper.SysCompanyMapper;
import com.example.springai.mapper.SysUserMapper;
import com.example.springai.service.CompanyExpiryReminderServiceI;
import com.example.springai.service.EmailServiceI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 到期提醒实现。
 *
 * <p>三个设计取舍，都是有理由的：
 * <ol>
 *   <li><b>窗口是"≤ 阈值且未发过"，不是"正好等于 30"</b>：按"正好等于"写，
 *       一旦某天发送失败（SMTP 抖一下），第二天变成 29 天就不再命中，那家公司永久漏提醒。
 *       用 ≤ + 标记补齐，失败次日自动重试。</li>
 *   <li><b>幂等标记落在库里（{@code remind_*_sent_at}），不用 Redis</b>：
 *       这是对外动作，Redis 过期/重启丢键会导致重复发信；而"发失败了"必须留空以便重试，
 *       所以也不能用一个"已处理"的布尔位。</li>
 *   <li><b>内部收件人没配 → 不发信、也不标记</b>：这样配好收件人之后，
 *       仍在窗口内的公司会被补发一次；若"没发也标记"，那批提醒就永久丢了。</li>
 * </ol>
 */
@Slf4j
@Service
public class CompanyExpiryReminderService implements CompanyExpiryReminderServiceI {

    /** 提醒档位（天）。与站内横幅、管理页徽章共用同一组窗口常量。 */
    static final int STAGE_30 = SysCompany.EXPIRING_SOON_DAYS;
    static final int STAGE_7 = SysCompany.FINAL_REMINDER_DAYS;

    @Autowired
    private SysCompanyMapper companyMapper;

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private EmailServiceI emailService;

    @Autowired
    private CompanyReminderProperties props;

    /**
     * 决定这家公司这一轮要不要提醒、提醒哪一档。返回 null 表示不发。
     *
     * <p><b>包级可见是为了能被单测直接钉住</b>（同 {@code UserDetailsServiceImpl.getRoleCode}
     * 的做法）—— 窗口与去重是这段逻辑里唯一需要精确的东西，放在 HTTP/DB 外面才好测。
     *
     * <p>几个刻意的边界：
     * <ul>
     *   <li>没签到期的（{@code null}）= 长期不限，永远不提醒；</li>
     *   <li>已经过期的**不再补发**：避免给一家半年前就过期的公司发"快到期了"。
     *       过期后的跟进是另一件事（公司管理页的"已到期"状态 + 人工催收）；</li>
     *   <li>进了 7 天窗口就**只发 7 档**，不再回头补 30 档 —— 否则一家公司会在最后几天
     *       连收两封语义重复的邮件（30 档那封的文案也只会写"还剩 3 天"，看着像发重了）；</li>
     *   <li>窗口是"≤ 阈值且该档未发过"，不是"正好等于阈值"：某天 SMTP 抖一下失败，
     *       第二天变成 29 天就不再命中，"正好等于"会让这家公司永久漏提醒。</li>
     * </ul>
     */
    static Integer decideStage(SysCompany company, LocalDateTime now) {
        Integer daysLeft = company.daysLeft(now);
        if (daysLeft == null || daysLeft < 0) {
            return null;
        }
        if (daysLeft <= STAGE_7) {
            return company.getRemind7SentAt() == null ? STAGE_7 : null;
        }
        if (daysLeft <= STAGE_30) {
            return company.getRemind30SentAt() == null ? STAGE_30 : null;
        }
        return null;
    }

    @Override
    public CompanyReminderResult runDailyReminder() {
        CompanyReminderResult result = new CompanyReminderResult();
        LocalDateTime now = AppTime.now();

        // 只看"正常"的公司：停用的公司提醒也没意义（续费要先恢复），
        // 而已过期的由 blockReason 拦在门外，这里不补发。
        List<SysCompany> companies = companyMapper.selectList(new QueryWrapper<SysCompany>()
                .eq("status", 1)
                .isNotNull("contract_expire_at"));
        result.setScanned(companies.size());

        for (SysCompany company : companies) {
            Integer stage = decideStage(company, now);
            if (stage == null) {
                continue;
            }
            CompanyReminderResult.Hit hit = new CompanyReminderResult.Hit(
                    company.getId(), company.getCompanyName(),
                    company.daysLeft(now), stage);
            hit.setExpireDate(company.getContractExpireAt().toLocalDate().toString());
            hit.setContractNote(company.getContractNote());
            hit.setSeatLimit(company.getSeatLimit());
            hit.setActiveMembers(userMapper.countActiveByCompany(company.getId()));
            result.getHits().add(hit);
        }

        if (result.getHits().isEmpty()) {
            log.info("📅 到期提醒：扫描 {} 家公司，没有命中提醒窗口", result.getScanned());
            return result;
        }

        // ---------- 内部汇总邮件 ----------
        List<String> recipients = cleanRecipients(props.getInternalRecipients());
        if (recipients.isEmpty()) {
            // 不标记：等配好收件人，这批仍在窗口内的公司会被补发一次
            log.warn("⚠️ 到期提醒命中 {} 家公司，但未配置内部收件人（app.company.expiry-reminder.internal-recipients），"
                            + "本次只记录不发信，也不标记已提醒: {}",
                    result.getHits().size(), briefNames(result.getHits()));
            sendClientMailsIfEnabled(result);
            return result;
        }

        String subject = "【到期提醒】" + result.getHits().size() + " 家客户即将到期";
        String html = buildInternalHtml(result.getHits());
        for (String to : recipients) {
            try {
                emailService.sendHtmlEmail(to, subject, html);
                result.setInternalSent(result.getInternalSent() + 1);
            } catch (Exception e) {
                log.error("❌ 到期提醒邮件发送失败 recipient={}: {}", to, e.getMessage());
            }
        }

        if (result.getInternalSent() > 0) {
            markSent(result.getHits(), now);
            result.setMarked(true);
            log.info("📅 到期提醒已发出: {} 家公司（30 天档 {} 家 / 7 天档 {} 家），收件人 {} 位",
                    result.getHits().size(), countStage(result.getHits(), STAGE_30),
                    countStage(result.getHits(), STAGE_7), result.getInternalSent());
        } else {
            log.error("❌ 到期提醒一封都没发出去（{} 个收件人全失败），不标记已提醒，明天重试",
                    recipients.size());
        }

        sendClientMailsIfEnabled(result);
        return result;
    }

    // ==================== 客户邮件（默认关闭） ====================

    private void sendClientMailsIfEnabled(CompanyReminderResult result) {
        if (!props.isNotifyClientAdmin()) {
            return;
        }
        for (CompanyReminderResult.Hit hit : result.getHits()) {
            List<SysUser> admins = userMapper.selectClientAdmins(hit.getCompanyId());
            if (admins.isEmpty()) {
                result.getClientIssues().add(hit.getCompanyName() + "：没有客户管理员账号");
                continue;
            }
            for (SysUser admin : admins) {
                String to = admin.getEmail();
                if (!isRealEmail(to)) {
                    // 导入时合成的 @imported.invalid 之类 —— 发出去只会退信
                    result.getClientIssues().add(hit.getCompanyName() + "：" + admin.getUsername()
                            + " 的邮箱是占位地址，已跳过");
                    continue;
                }
                try {
                    emailService.sendHtmlEmail(to,
                            "【服务到期提醒】" + hit.getCompanyName(),
                            buildClientHtml(hit));
                    result.setClientSent(result.getClientSent() + 1);
                } catch (Exception e) {
                    result.getClientIssues().add(hit.getCompanyName() + "：" + to + " 发送失败");
                    log.error("❌ 客户到期提醒发送失败 companyId={} to={}: {}",
                            hit.getCompanyId(), to, e.getMessage());
                }
            }
        }
        if (!result.getClientIssues().isEmpty()) {
            log.warn("⚠️ 客户侧到期提醒有 {} 处未送达（不影响内部提醒已发出）: {}",
                    result.getClientIssues().size(), result.getClientIssues());
        }
    }

    // ==================== 落库与工具 ====================

    /**
     * 标记"已提醒"。
     *
     * <p>按档位分别更新，且只更新命中该档的公司 —— 不要用"全表 set"，
     * 那会把没到窗口的公司的标记也写脏。
     */
    private void markSent(List<CompanyReminderResult.Hit> hits, LocalDateTime now) {
        for (CompanyReminderResult.Hit hit : hits) {
            UpdateWrapper<SysCompany> uw = new UpdateWrapper<SysCompany>().eq("id", hit.getCompanyId());
            if (hit.getStage() == STAGE_30) {
                uw.set("remind_30_sent_at", now);
            } else {
                uw.set("remind_7_sent_at", now);
            }
            companyMapper.update(null, uw);
        }
    }

    private static List<String> cleanRecipients(List<String> raw) {
        List<String> cleaned = new ArrayList<>();
        if (raw == null) {
            return cleaned;
        }
        for (String r : raw) {
            if (r == null || r.isBlank()) {
                continue;
            }
            String to = r.trim();
            if (!EmailFormat.isValid(to)) {
                log.warn("⚠️ 到期提醒收件人格式不正确，已忽略: {}", to);
                continue;
            }
            cleaned.add(to);
        }
        return cleaned;
    }

    /** 占位地址（RFC 2606 保留域名）不算真邮箱。批量导入时邮箱留空就会合成这种。 */
    private static boolean isRealEmail(String email) {
        return email != null && EmailFormat.isValid(email)
                && !email.toLowerCase().endsWith(".invalid");
    }

    private static long countStage(List<CompanyReminderResult.Hit> hits, int stage) {
        return hits.stream().filter(h -> h.getStage() == stage).count();
    }

    private static String briefNames(List<CompanyReminderResult.Hit> hits) {
        return hits.stream().map(CompanyReminderResult.Hit::getCompanyName).toList().toString();
    }

    private static String daysText(int daysLeft) {
        return daysLeft == 0 ? "今天到期" : "还剩 " + daysLeft + " 天";
    }

    // ==================== 邮件正文 ====================

    /**
     * 内部汇总邮件：一行一家公司，带上**内部才该看的信息**（备注、席位使用）。
     */
    private static String buildInternalHtml(List<CompanyReminderResult.Hit> hits) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"font-family:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;")
                .append("color:#0A2540;font-size:14px;line-height:1.7;\">")
                .append("<p>以下客户的服务即将到期，建议尽快沟通续费：</p>")
                .append("<table cellpadding=\"8\" cellspacing=\"0\" border=\"0\" ")
                .append("style=\"border-collapse:collapse;font-size:13px;\">")
                .append("<tr style=\"background:#F6F7FB;color:#425466;\">")
                .append("<th align=\"left\">客户</th><th align=\"left\">到期日</th>")
                .append("<th align=\"left\">剩余</th><th align=\"left\">席位(启用/上限)</th>")
                .append("<th align=\"left\">备注</th></tr>");
        for (CompanyReminderResult.Hit h : hits) {
            String seat = h.getSeatLimit() == null
                    ? h.getActiveMembers() + " / 不限"
                    : h.getActiveMembers() + " / " + h.getSeatLimit();
            sb.append("<tr style=\"border-top:1px solid #EDF0F4;\">")
                    .append("<td>").append(esc(h.getCompanyName())).append("</td>")
                    .append("<td>").append(h.getExpireDate()).append("</td>")
                    .append("<td style=\"color:").append(h.getDaysLeft() <= STAGE_7 ? "#D6483D" : "#B54708")
                    .append(";font-weight:600;\">").append(daysText(h.getDaysLeft())).append("</td>")
                    .append("<td>").append(seat).append("</td>")
                    .append("<td style=\"color:#8898AA;\">")
                    .append(esc(h.getContractNote() == null ? "-" : h.getContractNote())).append("</td>")
                    .append("</tr>");
        }
        sb.append("</table>")
                .append("<p style=\"color:#8898AA;font-size:12.5px;\">")
                .append("到期当天客户仍可使用，次日 00:00 起该公司所有账号将无法登录（资料保留）。")
                .append("续期只需在公司管理页把到期日往后改。</p>")
                .append("</div>");
        return sb.toString();
    }

    /**
     * 客户邮件：**绝不能带内部备注**（那是"年费、条款要点"，给客户看是事故），
     * 也不带其他客户的任何信息 —— 每家公司一封，只讲它自己。
     */
    private String buildClientHtml(CompanyReminderResult.Hit hit) {
        return "<div style=\"font-family:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;"
                + "color:#0A2540;font-size:14px;line-height:1.8;\">"
                + "<p>您好，</p>"
                + "<p>贵司（<b>" + esc(hit.getCompanyName()) + "</b>）的知识库服务将于 <b>"
                + hit.getExpireDate() + "</b> 到期（" + daysText(hit.getDaysLeft()) + "）。</p>"
                + "<p>" + esc(props.getClientContactText()) + "</p>"
                + "<p style=\"color:#8898AA;font-size:12.5px;\">"
                + "到期当天仍可正常使用，次日起账号将暂停登录，资料会完整保留。</p>"
                + "</div>";
    }

    private static String esc(String v) {
        return v == null ? "" : v.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
