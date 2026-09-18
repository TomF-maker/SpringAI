package com.example.springai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 到期提醒配置（{@code app.company.expiry-reminder.*}）。
 *
 * <p>与 {@code NewsProperties} 同一套哲学：**字段全给默认值，整个 yaml 段不写也能启动**；
 * 收件人为空时**只记日志、不发信**，而不是退回某个写死的地址。
 * 缺配置不该让应用起不来，也不该把邮件发到没人预期的地方。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.company.expiry-reminder")
public class CompanyReminderProperties {

    /** 总开关。关掉后定时任务直接返回（连扫描都不做）。 */
    private boolean enabled = true;

    /**
     * 内部收件人（伯伯公司自己人：顾问团队 / 财务）。
     *
     * <p><b>为空时只记日志、不发信，并且不标记"已提醒"</b> —— 这样等配好收件人之后，
     * 仍在窗口内的公司会被补发一次；若"没发也标记"，那批提醒就永久丢了。
     */
    private List<String> internalRecipients = new ArrayList<>();

    /**
     * 是否同时给客户公司的管理员发提醒邮件。**默认关闭**。
     *
     * <p>为什么默认关闭：这是**对外沟通**，措辞与时机应该由人来定；而且客户管理员的邮箱
     * 可能来自 xlsx 批量导入时合成的占位地址（{@code @imported.invalid}），发出去只会退信。
     * 打开后也会过滤掉占位地址。
     */
    private boolean notifyClientAdmin = false;

    /**
     * 客户邮件里"怎么联系我们"的那句话。
     *
     * <p>刻意做成配置而不是写死在模板里：商业化之前不该出现任何真实联系方式，
     * 上线前把这句话换成真实信息即可（与 {@code register.html} 的说明页同一个处理方式）。
     */
    private String clientContactText = "请联系与您对接的顾问续费，以免影响使用";
}
