package com.example.springai.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class LocalKnowledgeService implements LocalKnowledgeServiceI{

    private final Map<String, String> knowledgeBase = new HashMap<>();

    public LocalKnowledgeService() {
        initKnowledgeBase();
    }

    private void initKnowledgeBase() {
        // ---- 基础信息 ----
        knowledgeBase.put("你是谁", """
                🤖 我是**采购智能助手**，一个基于大模型的AI应用。
                我运行在您本地服务器上，由 Spring AI + Ollama + Qdrant 驱动。
                我的目标是帮助您高效处理采购相关的文档和问题。
                """);

        knowledgeBase.put("你能做什么", """
                📋 我可以帮您做以下事情：
                1. 📄 **文档问答**：上传 PDF/Word 文档，基于文档内容回答问题
                2. 🌤️ **查询天气**：实时查询任意城市的天气信息
                3. 📰 **获取新闻**：获取 AI 领域的热门资讯
                4. 🔍 **知识检索**：从已上传的文档中快速检索信息
                5. 💬 **智能对话**：多轮对话，记住上下文
                """);

        knowledgeBase.put("你支持什么功能", """
                ✅ 支持的功能：
                - PDF / Word 文档上传与解析
                - RAG（检索增强生成）智能问答
                - 流式输出（逐字显示回答）
                - 天气查询（实时 API）
                - AI 新闻抓取
                - 多轮对话记忆
                - OCR 图片文字识别（扫描版 PDF）
                """);

        knowledgeBase.put("怎么用", """
                📖 使用指南：
                1. 在对话框输入问题，直接提问
                2. 点击“选择PDF/Word”上传文档，上传后即可基于文档提问
                3. 询问天气：例如“深圳天气如何”
                4. 获取新闻：例如“最近AI有什么新闻”
                5. 所有回答都支持流式输出，实时查看
                """);

        // ---- 扩展：你可以在这里继续添加更多问答 ----
        knowledgeBase.put("你好", "👋 您好！我是采购智能助手，很高兴为您服务！");

        knowledgeBase.put("谢谢", "😊 不客气，随时为您服务！");

        knowledgeBase.put("你的技术栈", """
                🛠️ 技术栈：
                - 后端：Spring Boot 4.1 + Spring AI 2.0
                - 大模型：Ollama + qwen2.5:1.5b
                - 向量数据库：Qdrant
                - 文档解析：PDFBox + Apache POI + Tesseract OCR
                - 前端：Thymeleaf + SSE 流式输出
                - 部署：Ubuntu 22.04 + Docker
                """);
    }

    /**
     * 匹配本地知识库
     *
     * @param question 用户问题
     * @return 匹配的答案，如果未匹配返回 null
     */
    public String match(String question) {
        if (question == null || question.trim().isEmpty()) {
            return null;
        }

        String trimmed = question.trim();
        log.debug("🔍 匹配本地知识库: {}", trimmed);

        // 精确匹配
        for (Map.Entry<String, String> entry : knowledgeBase.entrySet()) {
            if (trimmed.contains(entry.getKey()) || entry.getKey().contains(trimmed)) {
                log.info("✅ 本地知识库命中: {}", entry.getKey());
                return entry.getValue();
            }
        }

        // 如果问题以"你是谁"、"你能"、"你支持"等开头，但未精确匹配，尝试模糊匹配
        if (trimmed.startsWith("你") || trimmed.startsWith("您") || trimmed.startsWith("什么")) {
            for (Map.Entry<String, String> entry : knowledgeBase.entrySet()) {
                if (trimmed.contains(entry.getKey().substring(0, Math.min(2, entry.getKey().length())))) {
                    log.info("✅ 本地知识库模糊命中: {}", entry.getKey());
                    return entry.getValue();
                }
            }
        }

        return null;
    }
}