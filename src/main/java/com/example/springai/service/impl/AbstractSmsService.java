package com.example.springai.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.example.springai.service.SmsServiceI;
import com.example.springai.utils.PhoneUtils;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

/**
 * 验证码的生成与校验（与具体短信通道无关）。
 *
 * <p>Redis key 用独立命名空间 {@code verify:phone:<scene>:<phone>}，
 * 与邮箱验证码的 {@code verify:code:<email>} 分开 —— 后者虽然前缀看着像重复，
 * 但那是刻意的（send-code 写、register 读同一个 key），不要去动它。
 */
@Slf4j
public abstract class AbstractSmsService implements SmsServiceI {

    protected static final String CODE_PREFIX = "verify:phone:";
    protected static final long CODE_EXPIRE_SECONDS = 300;

    @Autowired
    protected StringRedisTemplate redisTemplate;

    @Value("${app.sms.provider:log}")
    private String provider;

    /**
     * 启动时打印实际生效的通道。
     *
     * <p>三个实现是靠 {@code @ConditionalOnProperty} 装配的，同一时刻只有一个存在。
     * 切换通道要改配置并重启，很容易在自测时搞不清当前到底是哪个在发 —— 打一行日志省得猜。
     */
    @PostConstruct
    public void logActiveChannel() {
        log.info("📱 短信通道已启用: {}（app.sms.provider={}）", getClass().getSimpleName(), provider);
    }

    @Override
    public void sendCode(String phone, String scene) {
        String normalized = PhoneUtils.normalize(phone);
        if (normalized == null) {
            throw new RuntimeException("手机号格式不正确");
        }
        String code = RandomUtil.randomNumbers(6);
        redisTemplate.opsForValue()
                .set(codeKey(normalized, scene), code, CODE_EXPIRE_SECONDS, TimeUnit.SECONDS);

        deliver(normalized, code, scene);
    }

    @Override
    public boolean verifyCode(String phone, String scene, String code) {
        String normalized = PhoneUtils.normalize(phone);
        if (normalized == null || code == null) {
            return false;
        }
        // GETDEL：校验与消费必须是原子的。先 get 再 delete 存在竞态，
        // 两个并发请求可能同时校验通过（现有 VerificationCodeService 就有这个问题，别照抄）。
        String stored = redisTemplate.opsForValue().getAndDelete(codeKey(normalized, scene));
        return code.trim().equals(stored);
    }

    /** 校验通过后主动清除验证码（例如登录成功后）。 */
    public void clearCode(String phone, String scene) {
        String normalized = PhoneUtils.normalize(phone);
        if (normalized != null) {
            redisTemplate.delete(codeKey(normalized, scene));
        }
    }

    protected String codeKey(String normalizedPhone, String scene) {
        return CODE_PREFIX + scene + ":" + normalizedPhone;
    }

    /**
     * 把验证码投递给用户。
     *
     * @param phone 已归一化的手机号
     * @param code  6 位验证码
     * @param scene 场景，发送失败时用于回滚对应 Redis key
     */
    protected abstract void deliver(String phone, String code, String scene);
}
