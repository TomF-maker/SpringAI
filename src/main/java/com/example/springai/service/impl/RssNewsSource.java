package com.example.springai.service.impl;

import com.example.springai.common.AppTime;
import com.example.springai.config.NewsProperties;
import com.example.springai.entity.KbNews;
import com.example.springai.service.NewsSourceI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 通用 RSS / Atom 订阅源，喂哪些站由 {@code app.news.rss.feeds} 配置。
 *
 * <p>实测可用的三个：量子位、InfoQ 中文、开源中国。36氪和机器之心的
 * {@code /feed} 返回的是反爬 HTML 而不是 XML，抓不了；虎嗅直接连不上 ——
 * 这两个别往配置里加，会一直报"解析失败"。
 *
 * <p><b>手写解析而不是引 Rome</b>：源就三个、都是标准 RSS 2.0，
 * 需要的字段只有 title / link / description / pubDate。为此引一个依赖不划算
 * （项目连短信签名都是手写的，见 AliyunSmsService 的注释）。
 * 代价是 XML 的两件事必须自己做对，见下。
 */
@Slf4j
@Component
public class RssNewsSource implements NewsSourceI {

    /** 摘要截断长度。RSS 的 description 经常是整篇文章，全存进去既没用又占空间。 */
    private static final int SUMMARY_MAX = 500;

    @Autowired
    private NewsProperties props;

    @Override
    public String sourceType() {
        return KbNews.SOURCE_RSS;
    }

    @Override
    public List<KbNews> fetch() {
        List<KbNews> all = new ArrayList<>();
        List<NewsProperties.Feed> feeds = props.getRss().getFeeds();

        // 每个源单独 try/catch：**一个源挂了不该让整轮抓取没有新闻**。
        // 所有源都失败时下面统一抛，那次调用方会知道真的全挂了。
        int failed = 0;
        for (NewsProperties.Feed feed : feeds) {
            if (feed.getUrl() == null || feed.getUrl().isBlank()) {
                continue;
            }
            try {
                List<KbNews> items = fetchOne(feed);
                all.addAll(items);
                log.info("📰 RSS「{}」抓到 {} 条", feed.getName(), items.size());
            } catch (Exception e) {
                failed++;
                log.warn("⚠️ RSS「{}」抓取失败，跳过: {}", feed.getName(), e.getMessage());
            }
        }

        if (!feeds.isEmpty() && failed == feeds.size()) {
            // 全挂和"今天都没更新"是两回事，不能让后者把前者盖住
            throw new IllegalStateException("所有 RSS 源都抓取失败（共 " + failed + " 个）");
        }
        return all;
    }

    private List<KbNews> fetchOne(NewsProperties.Feed feed) throws Exception {
        String xml = buildRestTemplate().getForObject(feed.getUrl(), String.class);
        if (xml == null || xml.isBlank()) {
            throw new IllegalStateException("返回空响应");
        }
        return parseItems(xml, feed.getName());
    }

    /**
     * 解析一份 feed 的 XML。<b>与网络无关，所以能直接单测</b> ——
     * XXE 防护、日期格式、HTML 清洗这些最容易出错的地方都在这一层。
     */
    List<KbNews> parseItems(String xml, String feedName) throws Exception {
        Document doc = parseXml(xml);
        doc.getDocumentElement().normalize();

        // RSS 2.0 是 <item>，Atom 是 <entry>。两个都认，省得某个站换了格式就静默抓不到
        NodeList items = doc.getElementsByTagName("item");
        if (items.getLength() == 0) {
            items = doc.getElementsByTagName("entry");
        }
        if (items.getLength() == 0) {
            // 能解析成 XML 但一条都没有 —— 多半是反爬页或换了格式。
            // 抛出去而不是返回空列表：空列表会被当成"今天没新闻"，静默失效。
            throw new IllegalStateException("XML 里没有 item/entry 节点（是不是返回了 HTML 反爬页？）");
        }

        List<KbNews> result = new ArrayList<>();
        for (int i = 0; i < items.getLength(); i++) {
            Node node = items.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            KbNews news = toNews((Element) node, feedName);
            if (news != null) {
                result.add(news);
            }
        }
        return result;
    }

    private KbNews toNews(Element item, String feedName) {
        String title = stripHtml(childText(item, "title"));
        String link = childLink(item);
        if (title == null || title.isBlank() || link == null || link.isBlank()) {
            return null;   // 没标题或没链接的条目没法展示也没法去重
        }

        KbNews news = new KbNews();
        // RSS 没有稳定的 id（guid 很多站不给、给了也可能变），用 link 的哈希去重。
        // 标题会改、link 相对稳定。
        news.setDedupKey(sha256(link));
        news.setSourceType(KbNews.SOURCE_RSS);
        news.setSourceName(feedName);
        news.setTitle(title);
        news.setUrl(link);
        // RSS 没有分类和评分 —— 所以评分阈值过滤对本源不生效，这是有意的
        news.setCategory(null);
        news.setScore(null);
        news.setSummary(truncate(stripHtml(firstNonBlank(
                childText(item, "description"), childText(item, "summary")))));
        news.setPublishedAt(parseDate(firstNonBlank(
                childText(item, "pubDate"), childText(item, "updated"),
                childText(item, "published"), childText(item, "date"))));
        return news;
    }

    // ==================== XML ====================

    /**
     * 解析 XML，<b>关掉所有外部实体</b>。
     *
     * <p>这是本类最要紧的一段。默认配置下，一份带 {@code <!ENTITY xxe SYSTEM "file:///etc/passwd">}
     * 的 feed 能把服务器上的文件读进摘要里 —— 而 feed 的内容完全由第三方控制，
     * 配置里加一个源就等于信任那个站。这是 XXE，不是理论风险。
     *
     * <p>刻意**不设** {@code disallow-doctype-decl=true}：那个更严格，但会让
     * 少数带 DOCTYPE 声明的正常 feed 直接解析失败。下面三条覆盖了 XXE 的所有路径
     * （外部通用实体、外部参数实体、外部 DTD），同时保留对 DOCTYPE 的兼容。
     *
     * <p>这几条 {@code setFeature} 在 JDK 内置解析器上都支持。**故意不 try/catch** ——
     * 万一将来换了不支持的解析器，宁可当场报错，也不要静默地退回到无防护状态。
     */
    private static Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

        DocumentBuilder builder = factory.newDocumentBuilder();
        // 关掉它默认往 stderr 打的"解析错误"噪音，我们自己记日志
        builder.setErrorHandler(null);
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    /** 取直接子元素的文本。用直接子节点而不是 {@code getElementsByTagName}，后者是递归的，会串到嵌套结构里。 */
    private static String childText(Element parent, String tag) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && tag.equalsIgnoreCase(n.getNodeName())) {
                String text = n.getTextContent();
                return text == null || text.isBlank() ? null : text.trim();
            }
        }
        return null;
    }

    /**
     * 取条目链接。
     *
     * <p>RSS 2.0 是 {@code <link>https://...</link>}（文本），
     * Atom 是 {@code <link href="https://..." rel="alternate"/>}（属性）——
     * 只取文本的话 Atom 源会全部被判为"没链接"而丢弃。
     */
    private static String childLink(Element item) {
        String text = childText(item, "link");
        if (text != null) {
            return text;
        }
        NodeList children = item.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && "link".equalsIgnoreCase(n.getNodeName())) {
                String href = ((Element) n).getAttribute("href");
                if (href != null && !href.isBlank()) {
                    return href.trim();
                }
            }
        }
        return null;
    }

    // ==================== 文本清洗 ====================

    /**
     * 把 description 里的 HTML 洗成纯文本。
     *
     * <p>RSS 的 description 经常是整篇 HTML（带 {@code <p>}、{@code <img>}、
     * 甚至 {@code <script>}）。不洗的话：邮件里会串版，
     * 而且这段文本将来若进了向量库/RAG 会把标签一起喂给模型。
     */
    static String stripHtml(String html) {
        if (html == null) {
            return null;
        }
        // 先整块去掉 script/style 的内容，再去标签 —— 顺序反了会把标签里的代码留下来
        String s = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        // 块级标签的边界换成空格，否则 <p>a</p><p>b</p> 会粘成 "ab"
        s = s.replaceAll("(?i)</?(p|div|br|li|ul|ol|h[1-6]|tr|td|th|table|section|article|blockquote)\\b[^>]*>", " ");
        // 其余标签（<b> <a> <span> <img> 这类行内标签）直接删掉、**不加空格** ——
        // 加了的话 "这是<b>摘要</b>" 会变成 "这是 摘要"，凭空多出一个空格
        s = s.replaceAll("(?s)<[^>]*>", "");
        // &amp; 必须最后还原，否则 "&amp;lt;" 会被拆成 "<" 而不是 "&lt;"
        s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                .replace("&amp;", "&");
        s = s.replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? null : s;
    }

    private static String truncate(String s) {
        if (s == null || s.length() <= SUMMARY_MAX) {
            return s;
        }
        return s.substring(0, SUMMARY_MAX) + "…";
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /**
     * 解析发布时间。
     *
     * <p>RSS 用 RFC 822（{@code Tue, 15 Sep 2026 03:14:09 +0800}），
     * Atom 用 ISO-8601（{@code 2026-09-15T03:14:09Z}），还有各站自己发挥的变体。
     * 逐个试，都失败就返回 null —— <b>不要拿抓取时间顶替</b>：
     * 那会让"过去 24 小时"的筛选把陈年旧闻当成新新闻。
     */
    static LocalDateTime parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        // RFC 822 / RFC 1123：带 +0800 或 GMT
        try {
            return ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .withZoneSameInstant(AppTime.ZONE).toLocalDateTime();
        } catch (Exception ignored) {
            // 换下一种
        }
        // ISO-8601 带偏移：2026-09-15T03:14:09Z / +08:00
        try {
            return OffsetDateTime.parse(s).atZoneSameInstant(AppTime.ZONE).toLocalDateTime();
        } catch (Exception ignored) {
            // 换下一种
        }
        // 不带偏移的裸时间：按北京时间理解（国内源基本都是）
        try {
            return LocalDateTime.parse(s);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String sha256(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 自建带超时的 RestTemplate。
     *
     * <p>不用 {@code RestTemplateConfig} 那个共享 Bean —— 它是裸的 {@code new RestTemplate()}，
     * 超时是 0 = 无限等待。一个源不响应就会把整轮抓取（进而整个定时任务）永久挂住。
     */
    private RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getRss().getTimeoutMs());
        factory.setReadTimeout(props.getRss().getTimeoutMs());
        return new RestTemplate(factory);
    }
}
