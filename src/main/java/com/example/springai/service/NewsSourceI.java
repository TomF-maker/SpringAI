package com.example.springai.service;

import com.example.springai.entity.KbNews;

import java.util.List;

/**
 * 一个新闻源。实现类都是 {@code @Component}，{@code NewsDigestService} 注入
 * {@code List<NewsSourceI>} 全量跑一遍 —— 加新源只要写个类，不用改调用方。
 *
 * <p><b>契约：抓不到就抛异常，不要返回空列表。</b>
 * 两者在调用方眼里差别很大：空列表是"这个源今天没有新闻"（正常，比如小站点周末不更新），
 * 抛异常是"这个源坏了"（要记 WARN、要能被发现）。把故障伪装成"今天没新闻"，
 * 源挂了几个月都不会有人知道。
 */
public interface NewsSourceI {

    /** 源标识，用于启动日志、错误日志和 {@code source_type} 之外的人工排查。 */
    String sourceType();

    /**
     * 抓一次。
     *
     * @return 抓到的条目；**可能为空**（这个源今天确实没有新内容）
     * @throws Exception 网络失败、响应格式异常等真故障
     */
    List<KbNews> fetch() throws Exception;
}
