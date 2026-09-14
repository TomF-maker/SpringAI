package com.example.springai.utils;

import java.util.regex.Pattern;

/**
 * 手机号归一化与校验。
 *
 * <p>归一化很关键：如果 {@code +8613800138000} 和 {@code 138 0013 8000} 被当成两个
 * 不同的字符串，限流就会变成两个独立的桶指向同一个人，等于没有限流。
 */
public final class PhoneUtils {

    private PhoneUtils() {
    }

    /** 中国大陆手机号：1 开头，第二位 3-9，共 11 位。 */
    private static final Pattern CN_MOBILE = Pattern.compile("^1[3-9]\\d{9}$");

    /**
     * 去掉 +86 前缀、空格、横线、括号，返回 11 位号码。
     *
     * @return 归一化后的号码；不是合法号码时返回 null
     */
    public static String normalize(String phone) {
        if (phone == null) {
            return null;
        }
        String v = phone.trim()
                .replace("+86", "")
                .replace(" ", "")
                .replace("-", "")
                .replace("(", "")
                .replace(")", "");
        if (v.startsWith("86") && v.length() == 13) {
            v = v.substring(2);
        }
        return CN_MOBILE.matcher(v).matches() ? v : null;
    }

    public static boolean isValid(String phone) {
        return normalize(phone) != null;
    }

    /** 脱敏展示：138****8000。用于登录挑战响应，不要把完整号码回传给前端。 */
    public static String mask(String phone) {
        String v = normalize(phone);
        if (v == null) {
            return null;
        }
        return v.substring(0, 3) + "****" + v.substring(7);
    }
}
