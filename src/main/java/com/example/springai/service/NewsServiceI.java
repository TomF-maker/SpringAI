package com.example.springai.service;

/**
 * 聊天里的即时新闻查询。定时抓取与邮件推送在 {@link NewsDigestServiceI}。
 */
public interface NewsServiceI {

    /**
     * 抓取热门新闻。
     *
     * @param limit    返回条数，默认 5；实现里有自己的上限（见 NewsService）
     * @param window   时间窗口，"24h" 或 "7d"（接口只认这两个）
     * @param category 分类过滤：ai-models / ai-products / industry / paper / tip；
     *                 传 null 或空白表示不限
     * @return 格式化后的新闻列表（已按策展评分倒序）
     */
    String getAINews(int limit, String window, String category);
}
