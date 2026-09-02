package com.example.springai.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.example.springai.service.EmailServiceI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class EmailService implements EmailServiceI {

    @Autowired
    private JavaMailSender mailSender;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Value("${spring.mail.username}")
    private String fromEmail;

    private static final String CODE_PREFIX = "verify:code:";
    private static final long CODE_EXPIRE_SECONDS = 300; // 5分钟

    /**
     * 发送验证码
     */
    public void sendVerificationCode(String email) {
        // 生成6位数字验证码
        String code = RandomUtil.randomNumbers(6);

        // 存入 Redis
        redisTemplate.opsForValue().set(CODE_PREFIX + email, code, CODE_EXPIRE_SECONDS, TimeUnit.SECONDS);

        // 发送邮件
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromEmail);
        message.setTo(email);
        message.setSubject("【采购智能助手】验证码");
        message.setText("您的验证码是：" + code + "，5分钟内有效。请勿泄露给他人。");
        mailSender.send(message);
    }

    /**
     * 校验验证码
     */
    public boolean verifyCode(String email, String code) {
        String cached = redisTemplate.opsForValue().get(CODE_PREFIX + email);
        return code != null && code.equals(cached);
    }

    /**
     * 删除验证码（使用后清除）
     */
    public void deleteCode(String email) {
        redisTemplate.delete(CODE_PREFIX + email);
    }
}