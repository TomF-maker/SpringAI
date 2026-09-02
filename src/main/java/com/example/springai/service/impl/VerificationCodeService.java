package com.example.springai.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.example.springai.service.VerificationCodeServiceI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class VerificationCodeService implements VerificationCodeServiceI {

    private static final String CODE_PREFIX = "verify:code:";
    private static final long CODE_EXPIRE_SECONDS = 300; // 5分钟

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 生成并保存验证码
     */
    public String generateAndSave(String email) {
        String code = RandomUtil.randomNumbers(6);
        String key = CODE_PREFIX + email;
        stringRedisTemplate.opsForValue().set(key, code, CODE_EXPIRE_SECONDS, TimeUnit.SECONDS);
        return code;
    }

    /**
     * 验证验证码是否正确
     */
    public boolean verify(String email, String code) {
        if (email == null || code == null) {
            return false;
        }
        String key = CODE_PREFIX + email;
        String storedCode = stringRedisTemplate.opsForValue().get(key);
        if (storedCode == null) {
            return false;
        }
        // 验证通过后删除验证码（一次性使用）
        if (storedCode.equals(code)) {
            stringRedisTemplate.delete(key);
            return true;
        }
        return false;
    }
}