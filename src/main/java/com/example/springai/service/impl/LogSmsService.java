package com.example.springai.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 开发/联调用的短信通道：只打日志，不真正发送。
 *
 * <p>默认实现。这样在腾讯云签名与模板审核（约 1-2 个工作日）完成之前，
 * 整个登录挑战流程就能端到端跑通并测试，不必等外部依赖。
 *
 * <p>改为 {@code app.sms.provider=tencent} 即切换到真实短信。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.sms.provider", havingValue = "log", matchIfMissing = true)
public class LogSmsService extends AbstractSmsService {

    @Override
    protected void deliver(String phone, String code, String scene) {
        log.warn("【模拟短信】场景={} 手机号 {} 的验证码是 {}（{} 分钟内有效）。"
                        + "当前 app.sms.provider=log，未真实发送。",
                scene, phone, code, CODE_EXPIRE_SECONDS / 60);
    }
}
