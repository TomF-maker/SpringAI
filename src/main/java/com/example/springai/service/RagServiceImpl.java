package com.example.springai.service;


import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RagServiceImpl implements RagServiceI {

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    /**
     * 阻塞式 RAG 问答
     */
    @Override
    public String chatWithDocument(String question) {
        log.info("🔍 收到RAG问答请求: {}", question);
        long startTime = System.currentTimeMillis();

        // 1. 检索相关文档片段
        List<Document> relevantDocs = retrieveDocuments(question);

        if (relevantDocs.isEmpty()) {
            return "抱歉，在知识库中未找到与您问题相关的内容。请上传相关文档后再提问。";
        }

        // 2. 构建 Prompt
        String prompt = buildPrompt(relevantDocs, question);

        // 3. 调用大模型生成回答
        String answer = chatClientBuilder.build()
                .prompt()
                .user(prompt)
                .call()
                .content();

        long endTime = System.currentTimeMillis();
        log.info("✅ RAG问答完成，耗时: {}ms", endTime - startTime);

        return answer;
    }

    /**
     * 流式 RAG 问答
     */
    @Override
    public Flux<String> chatWithDocumentStream(String question) {
        log.info("🔍 收到流式RAG问答请求: {}", question);
        long startTime = System.currentTimeMillis();

        // 1. 检索相关文档片段（阻塞操作，但很快）
        List<Document> relevantDocs = retrieveDocuments(question);

        if (relevantDocs.isEmpty()) {
            return Flux.just("抱歉，在知识库中未找到与您问题相关的内容。请上传相关文档后再提问。");
        }

        // 2. 构建 Prompt
        String prompt = buildPrompt(relevantDocs, question);

        // 3. 流式调用大模型
        return chatClientBuilder.build()
                .prompt()
                .user(prompt)
                .stream()
                .content()
                .doOnComplete(() -> {
                    long endTime = System.currentTimeMillis();
                    log.info("✅ 流式RAG问答完成，耗时: {}ms", endTime - startTime);
                });
    }

    /**
     * 检索相关文档片段（抽取为公共方法）
     */
    private List<Document> retrieveDocuments(String question) {
        log.debug("📚 正在检索相关文档...");
        // topK = 3，减少上下文长度，提升速度
        List<Document> relevantDocs = vectorStore.similaritySearch(SearchRequest.builder().query(question).build());
        log.info("📚 检索到 {} 个相关文档片段", relevantDocs.size());

        if (!relevantDocs.isEmpty()) {
            for (int i = 0; i < relevantDocs.size(); i++) {
                Document doc = relevantDocs.get(i);
                String content = doc.getFormattedContent();
                String preview = content.length() > 100 ? content.substring(0, 100) + "..." : content;
                log.info("片段 {}: {}\n相似度: {}", i + 1, preview, doc.getMetadata().get("similarity_score"));
            }
        }

        return relevantDocs != null ? relevantDocs : List.of();
    }

    /**
     * 构建 Prompt（抽取为公共方法）
     */
    private String buildPrompt(List<Document> relevantDocs, String question) {
        String context = relevantDocs.stream()
                .map(Document::getFormattedContent)
                .collect(Collectors.joining("\n\n---\n\n"));

        log.info("📝 构建Prompt，上下文长度: {} 字符", context.length());

        return """
                请根据以下文档内容回答用户的问题。
                
                文档内容：
                %s
                
                用户问题：%s
                
                回答要求：
                1. 只能基于上述文档内容回答
                2. 如果文档中没有相关信息，请明确说明"文档中未找到相关信息"
                3. 回答要简洁、准确，并用中文
                4. 引用文档中的原文时，请用引号标注
                """.formatted(context, question);
    }
}