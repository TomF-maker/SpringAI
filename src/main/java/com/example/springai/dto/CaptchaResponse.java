package com.example.springai.dto;

import lombok.Data;

/**
 * 签发一张图形验证码的返回体。
 *
 * <p>用 DTO 而不是 {@code Map<String,Object>} —— Map 的 key 写错编译期不报错，
 * 而这里两个字段前端都要用，写错任何一个的表现都是"裂图"或"验证码错误"，很难查。
 */
@Data
public class CaptchaResponse {

    /** 验证码 id。发码接口要把它原样带回来，服务端据此找回答案。 */
    private String captchaId;

    /**
     * 图片本身，**完整 data URI**（{@code data:image/png;base64,....}），前端直接赋给
     * {@code img.src} 即可。
     *
     * <p>用 base64 而不是让前端去 GET 一个图片 URL，主要是为了**失败能说话**：
     * {@code <img src>} 加载失败只能显示一个碎图标，签发限流（429）、Redis 抖动（500）
     * 都没有地方展示；而且图片 URL 会被浏览器缓存，"点了刷新还是同一张图"是个经典坑。
     *
     * <p>反过来说，**这个字段就是明文答案**（图片里画的就是它），只能出现在这一次响应里，
     * 不入日志、不入审计表。
     */
    private String image;
}
