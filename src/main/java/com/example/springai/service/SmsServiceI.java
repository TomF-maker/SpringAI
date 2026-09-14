package com.example.springai.service;

/**
 * 短信验证码通道。
 *
 * <p>有两个实现，由 {@code app.sms.provider} 选择：
 * <ul>
 *   <li>{@code log}（默认）—— 验证码打到日志，不花钱、不需要审核，
 *       用于在腾讯云签名/模板审核通过前先把登录流程跑通。</li>
 *   <li>{@code tencent} —— 真实调用腾讯云短信。</li>
 * </ul>
 */
public interface SmsServiceI {

    /**
     * 生成验证码、写入 Redis 并发送。
     *
     * @param phone 手机号（内部会归一化）
     * @param scene 场景，如 register / login / bind；参与 Redis key，避免跨场景复用
     */
    void sendCode(String phone, String scene);

    /**
     * 校验并**一次性消费**验证码。
     *
     * @return 校验通过返回 true，同时验证码被删除
     */
    boolean verifyCode(String phone, String scene, String code);
}
