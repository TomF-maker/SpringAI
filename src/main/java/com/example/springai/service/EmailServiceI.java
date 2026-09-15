package com.example.springai.service;

public interface EmailServiceI {

    void sendVerificationCode(String email);

    boolean verifyCode(String email, String code);

    void deleteCode(String email);

    void sendPasswordResetEmail(String toEmail, String username, String newPassword);

    /**
     * 发一封 HTML 邮件（新闻摘要用）。
     *
     * <p>现有的几个方法都用 {@code SimpleMailMessage}，只能发纯文本。
     * 新闻摘要要分块、要加链接、要区分重点，纯文本排不出来。
     *
     * @param to      收件人；调用方保证非空
     * @param subject 主题
     * @param html    正文，**必须是已经转义过的 HTML** —— 新闻标题来自第三方源，
     *                直接拼进去等于让源站决定我们邮件长什么样
     */
    void sendHtmlEmail(String to, String subject, String html);
}
