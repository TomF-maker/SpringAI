package com.example.springai.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 登录审计日志：每次**成功**登录一行。
 *
 * <p>为什么单开一张表：{@code sys_user.last_login_ip} / {@code last_login_time}
 * 只存"最后一次"，答不了"这个账号最近一周从哪几个省登录过" —— 而那正是异地风控
 * 和 BI 都想看的角度。也不并进 {@code kb_question_log}：那张表是提问类 KPI 的
 * 单一数据源，混进登录事件会把分母污染掉。
 *
 * <p><b>ipAddress 和 ipPrefix 必须都在，不要合并成一个字段。</b>
 * 前者是完整 IP，只给归属地和排查用；后者是 /24 或 /64 网段令牌，
 * 与 {@code sys_user.last_login_ip}、Redis {@code login:ippref:*} 同源，
 * 用来和风控对账。风控那边**故意**用前缀比较以容忍运营商换 IP ——
 * 拿 ipAddress 去比会疯狂误报异地登录。
 *
 * <p>写入方必须吞掉一切异常：审计日志坏了绝不能把登录打死。
 * 见 {@code LoginLogService}。
 */
@Data
@TableName("kb_login_log")
public class KbLoginLog {

    /** 账号密码直接登录成功。 */
    public static final String TYPE_PASSWORD = "PASSWORD";
    /** 异地登录，走完手机验证码后才签发 token。 */
    public static final String TYPE_SMS_VERIFY = "SMS_VERIFY";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** 冗余存一份账号：用户被删之后这条审计记录仍要能追溯。 */
    private String username;
    private String loginType;
    /** 完整客户端 IP；内网或取不到时为 null。 */
    private String ipAddress;
    /** 网段令牌（IPv4 /24、IPv6 /64），与风控的 last_login_ip 同源。 */
    private String ipPrefix;
    private String ipCountry;
    private String ipProvince;
    private String ipCity;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
