package com.example.springai.service;

import reactor.core.publisher.Flux;

/**
 * RAG（检索增强生成）服务接口
 * <p>
 * 职责：
 * 1. 接收用户问题，从向量库中检索相关文档片段
 * 2. 将检索结果与问题组合成提示词
 * 3. 调用大模型生成基于文档的回答
 */
public interface RagServiceI {

    /**
     * 基于知识库的智能问答
     *
     * @param question 用户问题
     * @return 基于文档内容的回答
     */
    String chatWithDocument(String question);

    /**
     * 基于知识库的智能问答（流式输出）
     *
     * @param question 用户问题
     * @return 流式返回的回答片段
     */
    Flux<String> chatWithDocumentStream(String question);
}
