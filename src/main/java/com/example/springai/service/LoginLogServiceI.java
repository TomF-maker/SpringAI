package com.example.springai.service;

import com.example.springai.entity.SysUser;

/**
 * 登录审计。每次成功登录写一行，供 BI 报表和异地排查用。
 *
 * <p><b>契约：永不抛异常。</b>审计日志坏了绝不能影响登录 —— 调用点
 * {@code AuthController.issueToken} 是密码登录和异地验证登录两条路的唯一收口，
 * 在那里抛异常等于把所有人挡在门外。提问埋点失败只丢统计，这个会丢整个登录，
 * 所以要求比 {@code QuestionLogService} 更严格。
 */
public interface LoginLogServiceI {

    /**
     * 记一次成功登录。内部会同步解析归属地（登录不是延迟敏感路径）。
     *
     * @param user      登录用户
     * @param loginType {@code KbLoginLog.TYPE_PASSWORD} / {@code TYPE_SMS_VERIFY}
     * @param clientIp  完整客户端 IP，可为 null
     * @param ipPrefix  /24 或 /64 网段令牌，可为 null
     */
    void record(SysUser user, String loginType, String clientIp, String ipPrefix);
}
