package com.example.springai.common;

import java.util.regex.Pattern;

/**
 * 邮箱格式校验。
 *
 * <p><b>规则必须和前端一模一样</b>（三个模板里的 {@code EMAIL_RE} 是同一串正则）。
 * 两边不一致的后果是双向的：前端放过去的地址后端拒绝，用户看到一个和输入框无关的后端报错；
 * 或者前端拦住合法地址，用户根本没机会提交。改这里就一起改那边。
 *
 * <p>基底是 WHATWG HTML5 给 {@code <input type="email">} 用的那串正则
 * （也就是浏览器原生校验用的），只改了一处：**域名后面必须有一个 {@code .TLD}**。
 * 浏览器允许 {@code user@qq} 这种没有点的地址（协议上确实合法），但本系统发信走公网 SMTP，
 * 没有内网邮件域 —— 少一个点的地址 100% 是手滑，让它在提交前就被拦住。
 *
 * <p>为什么后端也要做一遍（前端已经拦了）：前端校验只是体验，改 JS 或直接打接口就能绕。
 * 而一个打错的邮箱会安静地存进 {@code sys_user}，那个账号从此**收不到验证码、也走不了找回密码**
 * —— 用户完全不知道是被自己一个错别字坑的，只会觉得"这系统发不出邮件"。
 *
 * <p>只管"像不像一个邮箱"，不管"这个邮箱存不存在"（那只有发信才知道，见 {@code EmailService}）。
 */
public final class EmailFormat {

    /**
     * 长度上限。
     *
     * <p>RFC 5321 允许 254，但 {@code sys_user.email} 是 {@code varchar(100)} ——
     * 这里按列宽兜底。不拦的话超长地址会一路走到 INSERT 才炸，
     * 报出来的是 MySQL 的 "Data too long"，和用户填错的地方对不上。
     */
    public static final int MAX_LENGTH = 100;

    /**
     * 正则的**源串**，公开出来只为一件事：让 {@code EmailFormatTest} 能拿它去和
     * 模板里那串 JS 逐字比对（{@code register.html} / {@code forgot-password.html}
     * 各有一份一模一样的 {@code EMAIL_RE}）。
     *
     * <p>跨语言的一致性没法靠编译器保证，只能靠这条测试。
     * 改这个串就得同时改那两个模板 —— 测试会告诉你漏了哪个。
     */
    public static final String PATTERN_SOURCE =
            "^[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+"
                    + "@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?"
                    + "(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*"
                    + "\\.[a-zA-Z]{2,}$";

    private static final Pattern PATTERN = Pattern.compile(PATTERN_SOURCE);

    private EmailFormat() {
    }

    /** null / 空白 / 超长 / 格式不对 → false。 */
    public static boolean isValid(String email) {
        if (email == null) {
            return false;
        }
        String value = email.trim();
        if (value.isEmpty() || value.length() > MAX_LENGTH) {
            return false;
        }
        return PATTERN.matcher(value).matches();
    }
}
