package com.example.springai.utils;

import java.security.SecureRandom;

/**
 * 随机强密码生成器
 */
public class PasswordGenerator {

    private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz";
    private static final String DIGITS = "0123456789";
    private static final String SPECIAL = "!@#$%^&*()_+-=<>?";

    private static final String ALL = UPPER + LOWER + DIGITS + SPECIAL;
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 生成包含大小写字母、数字和特殊字符的强密码
     * @param length 长度（建议至少 12）
     * @return 明文密码
     */
    public static String generateStrongPassword(int length) {
        if (length < 8) {
            throw new IllegalArgumentException("密码长度至少 8 位");
        }

        StringBuilder password = new StringBuilder(length);
        // 保证至少包含一个大写、一个小写、一个数字、一个特殊字符
        password.append(UPPER.charAt(RANDOM.nextInt(UPPER.length())));
        password.append(LOWER.charAt(RANDOM.nextInt(LOWER.length())));
        password.append(DIGITS.charAt(RANDOM.nextInt(DIGITS.length())));
        password.append(SPECIAL.charAt(RANDOM.nextInt(SPECIAL.length())));

        // 剩余字符从所有字符中随机选取
        for (int i = 4; i < length; i++) {
            password.append(ALL.charAt(RANDOM.nextInt(ALL.length())));
        }

        // 打乱顺序
        char[] chars = password.toString().toCharArray();
        for (int i = chars.length - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char temp = chars[i];
            chars[i] = chars[j];
            chars[j] = temp;
        }
        return new String(chars);
    }
}