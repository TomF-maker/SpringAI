package com.example.springai.service;

public interface LocalKnowledgeServiceI {
    /**
     * 匹配本地知识库
     *
     * @param question 用户问题
     * @return 匹配的答案，如果未匹配返回 null
     */
    String match(String question);
}
