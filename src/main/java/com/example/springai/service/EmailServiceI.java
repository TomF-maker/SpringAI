package com.example.springai.service;

public interface EmailServiceI {

    void sendVerificationCode(String email);

    boolean verifyCode(String email, String code);

    void deleteCode(String email);

    void sendPasswordResetEmail(String toEmail, String username, String newPassword);
}
