package com.example.springai.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.example.springai.service.EmailServiceI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;


@Slf4j
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

    @Override
    public void sendPasswordResetEmail(String toEmail, String username, String newPassword) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(toEmail);
            message.setSubject("【采购智能助手】密码已重置");
            message.setText(String.format(
                    "%s 您好，\n\n您的账户密码已被管理员重置。\n新密码为：%s\n\n请使用新密码登录系统，并尽快修改为您自己的密码。\n登录地址：http://124.221.251.183:8080/login\n\n如有疑问，请联系管理员。\n\n此邮件由系统自动发送，请勿回复。",
                    username,
                    newPassword
            ));
            mailSender.send(message);
            log.info("密码重置邮件已发送至 {}", toEmail);
        } catch (Exception e) {
            log.error("发送密码重置邮件失败: {}", e.getMessage(), e);
            throw new RuntimeException("发送邮件失败，但密码已重置，请手动告知用户。");
        }
    }
}