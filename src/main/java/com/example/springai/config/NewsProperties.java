package com.example.springai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 新闻模块配置（{@code app.news.*}）。
 *
 * <p>用 {@code @ConfigurationProperties} 而不是一堆 {@code @Value}：
 * 收件人、关键词、RSS 源列表都是<b>列表结构</b>，{@code @Value} 只能靠
 * {@code ${app.news.recipients[0]}} 这种带下标的写法，加一个源就要改代码。
 * 这里加源只改 yaml。
 *
 * <p>所有字段都给了默认值，所以 <b>yaml 里整个 {@code app.news} 段不写也能启动</b> ——
 * 只是收件人为空、不会发信。缺配置不应该让应用起不来。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.news")
public class NewsProperties {

    /** 是否启用每日抓取与推送。关掉后定时任务直接返回。 */
    private boolean enabled = true;

    /**
     * 收件人邮箱列表。
     *
     * <p><b>为空时任务只抓取入库、不发信</b> —— 这是有意的：可以先让新闻攒起来
     * 观察抓取质量，再决定发给谁，而不是一上来就往别人邮箱里发。
     */
    private List<String> recipients = new ArrayList<>();

    /**
     * 关注关键词。命中的新闻会带上 {@code matched_keywords}，在邮件里单独成块排在最前。
     *
     * <p><b>刻意不做成硬过滤</b>：关键词配窄了（比如只写「招投标」）会把整封邮件清空，
     * 而用户看到空邮件只会以为功能坏了。做成"置顶"既突出重点又不会漏掉别的。
     */
    private List<String> keywords = new ArrayList<>();

    /**
     * 评分下限（0-100）。低于它的 API 条目直接丢弃。
     *
     * <p>只作用于 API 条目 —— RSS 没有评分字段，一律保留。
     * 设为 0 或负数表示不按评分过滤。
     */
    private int minScore = 60;

    /** 邮件里每个分类最多列几条。超出的只报数量，不列正文。 */
    private int topPerCategory = 8;

    /** 单封邮件最多列几条（含重点关注块）。防止首次运行时几十条全塞进去。 */
    private int maxPerEmail = 60;

    private Aihot aihot = new Aihot();
    private Rss rss = new Rss();

    @Data
    public static class Aihot {
        private String baseUrl = "https://aihot.virxact.com/api/v1/items";
        /** 接口只认 24h / 7d，传别的值会返回空。 */
        private String window = "24h";
    }

    @Data
    public static class Rss {
        /** 每个源单独设超时；一个源挂了不该拖垮整轮抓取。 */
        private int timeoutMs = 8000;
        private List<Feed> feeds = new ArrayList<>();
    }

    @Data
    public static class Feed {
        /** 展示用的源名，会写进 kb_news.source_name。 */
        private String name;
        private String url;
    }
}
