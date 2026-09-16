package com.example.springai.service;

import com.example.springai.dto.CaptchaResponse;

/**
 * 图形验证码：签发与校验。
 *
 * <p>用途是给「发验证码」这一步加一道人力成本 ——
 * {@code /api/auth/send-code}（邮箱）和 {@code /api/auth/send-sms-code}（短信）
 * 在此之前对"谁在调用"没有任何要求，拿 Postman 直接 POST 就能给**任意**邮箱/手机号触发发信。
 *
 * <p><b>它不是防刷的主体。</b>它的作用只是让脚本化滥用需要人认图；
 * 真正的量由 {@code EmailCodeRateLimiter} / {@code SmsRateLimiter} 兜底。
 * 别为了"更安全"把图调得更难认 —— 那是拿用户体验换零收益。
 *
 * <p>实现约束（每一条都是踩出来的，改之前先看实现类的注释）：
 * <ol>
 *   <li>校验成功后**必须原子地**消费掉它，否则同一个解出来的码能被并发重放多次；</li>
 *   <li>未知 id **不能写任何 key**，否则随机 id 就能刷出无限 Redis key；</li>
 *   <li>有**尝试次数上限**，否则 4 位码可以被脚本爆破。</li>
 * </ol>
 */
public interface CaptchaServiceI {

    /**
     * 签发一张新验证码。
     *
     * @param clientIp 客户端完整 IP，可为 null。仅用于签发限流，不落库、不入日志
     * @return 验证码 id + 图片（完整 data URI）
     */
    CaptchaResponse issue(String clientIp);

    /**
     * 校验并**消耗**一张验证码。
     *
     * <p>返回 true 时该验证码已被销毁，同一个 id 再调必然返回 false。
     *
     * <p>注意这里**不感知 {@code app.captcha.enabled} 开关** ——
     * 它永远说真话。开关只放在 {@code AuthController.requireCaptcha} 一处，
     * 否则读代码的人要为每个调用点多想一次"这个 true 是真通过了还是被关掉了"。
     */
    boolean verify(String captchaId, String code);
}
