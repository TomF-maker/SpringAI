package com.example.springai.utils;

import cn.hutool.core.convert.Convert;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 问题归一化 —— 用于"热门问题 Top N"的分组。
 *
 * <p>直接按原文分组会产生大量只出现一次的桶，所以要做一些廉价的归一。
 * 这也正是为什么归一化结果要在**写入时**就算好存下来：Top-N 查询才能是一条
 * 带索引的 SQL，之后还能离线 {@code UPDATE} 重调规则。
 *
 * <p><b>为什么不引入中文分词（HanLP / jieba）</b>：分词给的是"词频"而不是"热门问题"。
 * "如何申请报销"和"报销申请流程"分词后仍然落在两个桶里，要合并得靠意图聚类
 * （embedding 或 SimHash），那是另一个依赖、另一个项目。而 HanLP 还要几百 MB 模型
 * 和首次运行的词典下载，这台机器上还跑着 Tess4J 和 Qdrant。
 * 如果以后真的需要 v2，便宜的路子是字符二元组 SimHash + 汉明距离分桶。
 */
public final class QuestionNormalizer {

    private QuestionNormalizer() {
    }

    /** 标点与符号：一次正则去掉，这一步对合并桶的收益最大。 */
    private static final Pattern PUNCTUATION = Pattern.compile("[\\p{P}\\p{S}\\s]+");

    /** 开头客套话。列表刻意保持很短。 */
    private static final List<String> LEADING_NOISE = List.of(
            "请问一下", "请问", "我想问一下", "我想问", "想请问", "帮我看看", "帮我",
            "麻烦问一下", "麻烦", "咨询一下", "咨询", "问一下", "想问下", "想问"
    );

    /**
     * 句尾语气词。
     *
     * <p>只去语气词，**不去** 如何/怎么/是什么 —— 那会把语义不同的问题合并
     * （"如何报销" vs "报销"），悄悄让 Top-N 变错。
     */
    private static final List<String> TRAILING_NOISE = List.of(
            "吗", "呢", "吧", "啊", "呀", "哦", "么", "嘛", "哈", "呢？"
    );

    private static final int MAX_LENGTH = 255;

    /**
     * @return 归一化后的问题；输入为空时返回 null
     */
    public static String normalize(String question) {
        if (question == null) {
            return null;
        }
        String v = Convert.toDBC(question).trim().toLowerCase();
        if (v.isEmpty()) {
            return null;
        }

        // 先去掉客套前缀（在去标点之前，否则"请问，..."里的逗号会把前缀割断）
        for (String noise : LEADING_NOISE) {
            if (v.startsWith(noise)) {
                v = v.substring(noise.length());
                break;
            }
        }

        v = PUNCTUATION.matcher(v).replaceAll("");
        if (v.isEmpty()) {
            return null;
        }

        // 句尾语气词要去到不再命中为止（"是什么吗" → "是什么"）
        boolean changed = true;
        while (changed && v.length() > 1) {
            changed = false;
            for (String noise : TRAILING_NOISE) {
                if (v.length() > noise.length() && v.endsWith(noise)) {
                    v = v.substring(0, v.length() - noise.length());
                    changed = true;
                    break;
                }
            }
        }

        v = v.trim();
        if (v.isEmpty()) {
            return null;
        }
        return v.length() > MAX_LENGTH ? v.substring(0, MAX_LENGTH) : v;
    }
}
