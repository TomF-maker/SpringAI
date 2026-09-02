package com.example.springai.service;

public interface VerificationCodeServiceI {

    String generateAndSave(String email);

    boolean verify(String email, String code);


}
