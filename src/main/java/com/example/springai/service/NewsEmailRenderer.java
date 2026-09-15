package com.example.springai.service;

import com.example.springai.entity.KbNews;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 把一批新闻渲染成 HTML 邮件正文。
 *
 * <p><b>本类的第一条纪律：所有来自新闻源的文本都要转义。</b>标题、摘要、来源名
 * 全都由第三方 feed 决定 —— 不转义的话，一个标题写成
 * {@code <img src=x onerror=...>} 的源就能往订阅者的邮件里注入任意 HTML。
 * 这和 {@code users.html} 那个存储型 XSS 是同一类问题，只是载体从页面变成了邮件。
 *
 * <p>样式全部写成行内 style：邮件客户端对 {@code <style>} 块的支持参差不齐
 * （Gmail 会直接剥掉），写在头部的类选择器基本不生效。
 */
@Component
public class NewsEmailRenderer {

    /** 摘要再长也不该把一条新闻撑成半屏。入库时已截到 500，这里再收一道。 */
    private static final int SUMMARY_MAX = 180;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日");

    /**
     * 分类的展示顺序与中文名。
     *
     * <p>用 {@link LinkedHashMap} 而不是普通 Map —— 顺序就是邮件里的分块顺序，
     * 而 HashMap 的顺序是不确定的，那会导致同一天的两封邮件排版不一样。
     *
     * <p>最后的 {@code null} 键是"没有分类"的桶：RSS 条目的 category 为 null，
     * API 在 {@code mode=all} 下也会混进无分类的条目。它们归到"技术资讯"。
     */
    private static final Map<String, String> CATEGORY_LABELS = new LinkedHashMap<>();

    static {
        CATEGORY_LABELS.put("ai-models", "🧠 AI 模型");
        CATEGORY_LABELS.put("ai-products", "🚀 AI 产品");
        CATEGORY_LABELS.put("industry", "🏭 行业动态");
        CATEGORY_LABELS.put("paper", "📄 论文研究");
        CATEGORY_LABELS.put("tip", "💡 观点与技巧");
        CATEGORY_LABELS.put(null, "📡 技术资讯");
    }

    /** 关注关键词块单独排在最前，标签固定。 */
    private static final String FOCUS_LABEL = "⭐ 重点关注";

    /**
     * @param items      本次要展示的条目（已按评分倒序）
     * @param date       邮件日期，用于标题
     * @param pending    这一批之外还有多少条没列进来（超出每类上限的部分）
     */
    public String render(List<KbNews> items, LocalDate date, int pending) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"></head>")
          .append("<body style=\"margin:0;padding:0;background:#f4f6f9;\">")
          .append("<div style=\"max-width:680px;margin:0 auto;padding:20px;")
          .append("font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','Microsoft YaHei',sans-serif;")
          .append("color:#1e293b;font-size:14px;line-height:1.65;\">");

        appendHeader(sb, date, items.size(), pending);

        Set<String> knownCategories = CATEGORY_LABELS.keySet().stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // 重点关注：命中配置关键词的条目。它们**同时也留在各自的分类里**吗？
        // 不留 —— 重复出现会让邮件显得比实际内容多，而且用户会以为抓重了。
        List<KbNews> focus = items.stream()
                .filter(n -> notBlank(n.getMatchedKeywords()))
                .collect(Collectors.toList());

        appendSection(sb, FOCUS_LABEL, focus, true);
        for (Map.Entry<String, String> entry : CATEGORY_LABELS.entrySet()) {
            String category = entry.getKey();
            List<KbNews> group = items.stream()
                    .filter(n -> !notBlank(n.getMatchedKeywords()))
                    .filter(n -> category == null
                            ? n.getCategory() == null
                            : category.equals(n.getCategory()))
                    .collect(Collectors.toList());
            appendSection(sb, entry.getValue(), group, false);
        }

        // 兜底：源站加了新分类时，那些条目不能凭空消失。
        // 注意要把 null 分类排除掉 —— 它已经在「技术资讯」那一桶里了，
        // 这里再算一次就会重复列出。
        List<KbNews> unknown = items.stream()
                .filter(n -> !notBlank(n.getMatchedKeywords()))
                .filter(n -> n.getCategory() != null && !knownCategories.contains(n.getCategory()))
                .collect(Collectors.toList());
        appendSection(sb, "📎 未分类", unknown, false);

        appendFooter(sb);
        sb.append("</div></body></html>");
        return sb.toString();
    }

    private void appendHeader(StringBuilder sb, LocalDate date, int count, int pending) {
        sb.append("<div style=\"background:#1a73e8;border-radius:10px;padding:18px 22px;color:#fff;\">")
          .append("<div style=\"font-size:18px;font-weight:600;\">📰 每日新闻摘要</div>")
          .append("<div style=\"font-size:13px;opacity:.9;margin-top:4px;\">")
          .append(esc(date.format(DATE_FMT)))
          .append(" · 本期 ")
          .append(count)
          .append(" 条");
        if (pending > 0) {
            sb.append("（另有 ").append(pending).append(" 条未列出）");
        }
        sb.append("</div></div>");
    }

    private void appendSection(StringBuilder sb, String label, List<KbNews> items, boolean focus) {
        if (items.isEmpty()) {
            return;
        }
        sb.append("<div style=\"margin-top:22px;\">")
          .append("<div style=\"font-size:15px;font-weight:600;padding-bottom:8px;")
          .append("border-bottom:2px solid ").append(focus ? "#fbbc04" : "#e2e8f0").append(";\">")
          .append(esc(label))
          .append("<span style=\"color:#94a3b8;font-weight:400;font-size:13px;\"> · ")
          .append(items.size()).append(" 条</span></div>");

        for (KbNews n : items) {
            appendItem(sb, n, focus);
        }
        sb.append("</div>");
    }

    private void appendItem(StringBuilder sb, KbNews n, boolean focus) {
        sb.append("<div style=\"padding:12px 0;border-bottom:1px solid #f1f5f9;\">");

        // 标题
        sb.append("<div style=\"font-size:14px;font-weight:600;line-height:1.5;\">");
        if (notBlank(n.getUrl())) {
            // href 也要转义：URL 里的引号能闭合属性、注入 onmouseover 之类
            sb.append("<a href=\"").append(esc(n.getUrl()))
              .append("\" style=\"color:").append(focus ? "#b45309" : "#1a73e8")
              .append(";text-decoration:none;\">").append(esc(n.getTitle())).append("</a>");
        } else {
            sb.append(esc(n.getTitle()));
        }
        sb.append("</div>");

        // 元信息：来源 / 评分
        sb.append("<div style=\"font-size:12px;color:#94a3b8;margin-top:4px;\">")
          .append(esc(n.getSourceName() == null ? "未知来源" : n.getSourceName()));
        if (n.getScore() != null) {
            sb.append(" · 评分 ").append(n.getScore());
        }
        if (notBlank(n.getMatchedKeywords())) {
            sb.append(" · <span style=\"color:#b45309;\">命中 ").append(esc(n.getMatchedKeywords())).append("</span>");
        }
        sb.append("</div>");

        // 摘要
        if (notBlank(n.getSummary())) {
            sb.append("<div style=\"font-size:13px;color:#475569;margin-top:6px;\">")
              .append(esc(clip(n.getSummary())))
              .append("</div>");
        }

        // 入选理由（仅 API 条目有）
        if (notBlank(n.getReason())) {
            sb.append("<div style=\"font-size:12px;color:#94a3b8;margin-top:6px;")
              .append("padding-left:8px;border-left:2px solid #e2e8f0;\">")
              .append(esc(clip(n.getReason())))
              .append("</div>");
        }

        sb.append("</div>");
    }

    private void appendFooter(StringBuilder sb) {
        sb.append("<div style=\"margin-top:26px;padding-top:14px;border-top:1px solid #e2e8f0;")
          .append("font-size:12px;color:#94a3b8;\">")
          .append("本邮件由采购智能助手自动发送，收件人配置见 <code>app.news.recipients</code>。")
          .append("</div>");
    }

    // ==================== 工具 ====================

    /**
     * HTML 转义。
     *
     * <p>顺序要紧：{@code &} 必须最先替换，否则后面替换产生的 {@code &amp;}
     * 会被再转一次变成 {@code &amp;amp;}。
     */
    static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String clip(String s) {
        if (s == null || s.length() <= SUMMARY_MAX) {
            return s;
        }
        return s.substring(0, SUMMARY_MAX) + "…";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
