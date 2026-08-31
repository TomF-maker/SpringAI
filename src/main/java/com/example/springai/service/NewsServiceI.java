package com.example.springai.service;

public interface NewsServiceI {

    /**
     * 抓取热门新闻头条
     *
     * @param limit 返回的新闻条数，默认 5 条
     * @return 格式化后的新闻列表
     */
    String getAINews(int limit, String window);
}
