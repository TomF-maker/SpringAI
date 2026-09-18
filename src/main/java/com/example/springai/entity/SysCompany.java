package com.example.springai.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 公司。用户通过 {@code sys_user.company_id} 挂靠到这里。
 *
 * <p><b>为什么公司要独立成表，而不是在 sys_user 上存一个公司名字符串</b>：
 * 第一版就是那样做的，结果是"一家公司只能注册进去一个人" —— 因为信用代码
 * 是**公司的属性**，在用户表上对它做唯一约束，等于假设"一个代码只对应一个人"。
 * 同事之间的第二次注册会被自己人挡在门外，而报错还说"该信用代码已被注册"，
 * 他根本看不出是自己同事先注册了。
 *
 * <p>独立成表之后同事自动落到同一条公司记录上，唯一约束也落在它该在的地方
 * （信用代码唯一）。"每公司多少人"变成一次干净的 JOIN。
 *
 * <p>注册时按信用代码 find-or-create，见 {@code CompanyService.resolveOrCreate}。
 * 信用代码的格式与校验位校验在 {@code common.CreditCode}。
 */
@Data
@TableName("sys_company")
public class SysCompany {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 公司名称。取自**第一个**用这个信用代码注册的人填的写法。 */
    private String companyName;

    /** 统一社会信用代码（GB 32100，18 位）。全局唯一 —— 这是公司的身份，不是人的。 */
    private String creditCode;

    /** 1=正常 0=停用。停用后该公司所有员工不能登录，但**资料一律保留**。 */
    private Integer status;

    /** 合约到期日。null = 不限（未签或长期）。过期等同停用。 */
    private LocalDateTime contractExpireAt;

    /** 席位上限 = 该公司**启用中**的账号数上限。null = 不限。 */
    private Integer seatLimit;

    /** 合约备注（年费、条款要点）。纯文本，给人看。 */
    private String contractNote;

    /**
     * 「还剩 30 天」提醒已发送时间。null = 未发（含"发了但失败"，次日会重试）。
     *
     * <p><b>这两个字段必须显式写 {@code @TableField}。</b>MyBatis-Plus 的驼峰转下划线
     * 把 {@code remind30SentAt} 转成 {@code remind30_sent_at}（**数字前不插下划线**），
     * 而库里的列名是 {@code remind_30_sent_at} —— 不写注解就在运行时报
     * `Unknown column 'remind30_sent_at'`，而且是**每次查公司都报**（登录都会挂）。
     * 这类"实体字段名与列名不一致"的错不会在编译期暴露。
     */
    @TableField("remind_30_sent_at")
    private LocalDateTime remind30SentAt;

    /** 「还剩 7 天」提醒已发送时间。null = 未发。原因同上，注解不能省。 */
    @TableField("remind_7_sent_at")
    private LocalDateTime remind7SentAt;

    /**
     * "即将到期"的窗口（天）。
     *
     * <p>一个常量管三处：站内横幅、公司管理页的黄色徽章、到期提醒邮件的第一档。
     * 散开写 30 的话，改窗口时必然漏掉一处 —— 而漏掉的那处不报错，只是"这里说
     * 还剩 30 天、那里说还剩 20 天"，看着像 bug 但谁也说不清哪个对。
     */
    public static final int EXPIRING_SOON_DAYS = 30;

    /** 最后一次提醒的窗口（天）：进入这一档后邮件语气升级为"要紧"。 */
    public static final int FINAL_REMINDER_DAYS = 7;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 该公司当前能不能用（停用 / 到期）。返回 null 表示正常，否则是给用户看的原因。
     *
     * <p><b>判定只写这一份。</b>它同时被三处调用：登录（{@code AuthController}）、
     * 每个请求（{@code UserDetailsServiceImpl} 经 {@code JwtAuthenticationFilter}）、
     * 批量开户（{@code UserImportService}）。散开写必然有一天只改了其中一处 ——
     * 那种漏法不会报错，只会让"停用了却还能用"或"续费了却进不来"。
     *
     * <p>停用与到期给**不同的话术**：客户看到"已到期"会去联系续费，
     * 看到"已停用"会先来问为什么不让他用 —— 后者是我们要主动解释的场景。
     */
    public String blockReason(LocalDateTime now) {
        if (status != null && status == 0) {
            return "贵司账号已停用，请联系服务商";
        }
        if (contractExpireAt != null && contractExpireAt.isBefore(now)) {
            return "贵司服务已于 " + contractExpireAt.toLocalDate() + " 到期，请联系服务商续费";
        }
        return null;
    }

    /**
     * 距离到期还有几天（按**日期**算，不按小时）。
     *
     * <p>为什么按日期：到期日存的是当天 23:59:59，若按小时算，"还剩 30 天"这个窗口
     * 会落在某个时刻而不是某一天 —— 定时任务每天只跑一次，很容易整个窗口都错过。
     * 按日期算，9-18 看 10-18 就是整 30 天，判定稳定。
     *
     * <p>返回 null 表示不限（未签或长期）；返回负数表示已过期。
     */
    public Integer daysLeft(LocalDateTime now) {
        if (contractExpireAt == null) {
            return null;
        }
        return (int) java.time.temporal.ChronoUnit.DAYS.between(
                now.toLocalDate(), contractExpireAt.toLocalDate());
    }

    /**
     * 是否已进入"即将到期"窗口（含今天到期）。
     *
     * <p>已经过期的返回 false —— 那种情况由 {@link #blockReason} 表达，
     * 界面上要显示的是"已到期"而不是"即将到期"。
     */
    public boolean expiringSoon(LocalDateTime now) {
        Integer days = daysLeft(now);
        return days != null && days >= 0 && days <= EXPIRING_SOON_DAYS;
    }
}
