package com.example.springai.common;

/**
 * 统一社会信用代码（GB 32100-2015）校验。
 *
 * <p><b>为什么必须验校验位，而不能只验 18 位格式</b>：18 位字母数字随手就能编一个，
 * 用户的错别字也同样是"18 位合法字符"。而信用代码是"每个公司多少人"这个统计的分组键 ——
 * 一个错字就把一家公司劈成两行，报表上看不出来。末位校验码正好拦住绝大多数手误。
 *
 * <p><b>字符集不是 36 进制</b>：GB 32100 刻意排除了 {@code I O S V Z}
 * 这五个易混字符，所以是 <b>31 进制</b>。用 {@code Character.isLetterOrDigit} 之类的
 * 宽泛判断会把非法字符放过去。
 *
 * <p>算法：前 17 位各自查表取 0..30 的值，乘对应权重求和；
 * 校验值 = {@code 31 - (和 % 31)}（结果为 31 时记为 0），再查表还原成字符，与第 18 位比对。
 */
public final class CreditCode {

    /** GB 32100 的 31 进制字符集，顺序即字符的取值。注意没有 I、O、S、V、Z。 */
    private static final String CHARSET = "0123456789ABCDEFGHJKLMNPQRTUWXY";

    /** 前 17 位的权重，顺序固定。 */
    private static final int[] WEIGHTS = {
            1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28
    };

    private static final int LENGTH = 18;
    private static final int MODULUS = 31;

    private CreditCode() {
    }

    /**
     * 归一化：去空白 + 转大写。库里存的就是这个形式，查重和比对都基于它，
     * 否则 {@code "9135...y43"} 和 {@code "9135...Y43"} 会被当成两个不同的码。
     *
     * @return 归一化后的字符串；入参为 null 时返回 null
     */
    public static String normalize(String raw) {
        return raw == null ? null : raw.trim().toUpperCase();
    }

    /** 是否是合法的统一社会信用代码。null、空、长度不对、含非法字符、校验位错都返回 false。 */
    public static boolean isValid(String raw) {
        String code = normalize(raw);
        if (code == null || code.length() != LENGTH) {
            return false;
        }
        // 校验位必须能查到值；查不到说明含非法字符（含被排除的 I/O/S/V/Z）
        if (CHARSET.indexOf(code.charAt(LENGTH - 1)) < 0) {
            return false;
        }

        int sum = 0;
        for (int i = 0; i < LENGTH - 1; i++) {
            int value = CHARSET.indexOf(code.charAt(i));
            if (value < 0) {
                return false;
            }
            sum += value * WEIGHTS[i];
        }

        int remainder = sum % MODULUS;
        int expected = (MODULUS - remainder) % MODULUS;
        return CHARSET.charAt(expected) == code.charAt(LENGTH - 1);
    }
}
