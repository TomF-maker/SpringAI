package com.example.springai.service;


import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档处理服务实现类
 * <p>
 * 职责：
 * 1. 解析 PDF 文档，提取纯文本内容
 * 2. 将长文本按策略切分成适合向量化的片段
 * 3. 构建 Document 对象（含元数据）
 * 4. 调用 VectorStore 完成向量化存储
 * <p>
 * 技术选型说明：
 * - Apache PDFBox：用于 PDF 文本提取，轻量且无依赖
 * - Spring AI Document：统一的文档抽象，包含内容和元数据
 * - Spring AI VectorStore：自动调用 EmbeddingModel 完成向量化
 */
@Service
@Slf4j
public class DocumentServiceImpl implements DocumentServiceI {

    /**
     * 向量存储接口，由 Spring AI 自动注入
     * 实际实现类由 spring-ai-starter-vector-store-qdrant 提供
     * 作用：负责向量的存入和检索
     */
    @Autowired
    private VectorStore vectorStore;

    /**
     * 嵌入模型接口，由 Spring AI 自动注入
     * 实际使用的是 Ollama 中的 nomic-embed-text 模型
     * 作用：将文本转换为向量
     */
    @Autowired
    private EmbeddingModel embeddingModel;

    /**
     * 处理上传的PDF文档
     * <p>
     * 执行流程：
     * 1. 提取PDF文本 -> extractTextFromPDF()
     * 2. 文本切分 -> splitIntoChunks()
     * 3. 构建Document对象 -> 组装内容和元数据
     * 4. 存入向量库 -> vectorStore.add()
     *
     * @param file 上传的PDF文件
     * @return 切分后的文档片段数量
     * @throws IOException 文件读取异常
     */
    @Override
    public int processDocument(MultipartFile file) throws IOException {
        String fileName = file.getOriginalFilename();
        log.info("开始处理文档: {}", fileName);
        long startTime = System.currentTimeMillis();

        // 1. 提取PDF文本
        log.debug("正在提取PDF文本...");
        String fullText = extractTextFromPDF(file);

        if (fullText == null || fullText.trim().isEmpty()) {
            log.warn("⚠️ PDF文件内容为空: {}", fileName);
            throw new IOException("PDF文件内容为空或无法提取文本");
        }
        log.debug("✅ 文本提取完成，共 {} 个字符", fullText.length());

        // 2. 文本切分
        log.debug("✂️ 正在切分文本...");
        List<String> chunks = splitIntoChunks(fullText, 500);
        log.info("✂️ 文本切分完成，共 {} 个片段", chunks.size());

        // 3. 构建Document对象
        log.debug("📦 正在构建Document对象...");
        List<Document> documents = buildDocuments(chunks, fileName);

        // 4. 存入向量库
        log.info("💾 正在存入向量库，共 {} 个文档...", documents.size());
        vectorStore.add(documents);

        long endTime = System.currentTimeMillis();
        log.info("✅ 文档处理完成: {}，共 {} 个片段，耗时 {}ms",
                fileName, documents.size(), (endTime - startTime));

        return documents.size();
    }

    /**
     * 使用PDFBox提取PDF中的文本内容
     * <p>
     * 工作原理：
     * PDDocument.load() 加载PDF文件
     * PDFTextStripper 按页面顺序提取纯文本
     * <p>
     * 注意事项：
     * - 扫描版PDF（图片格式）无法提取文本，需要OCR
     * - 加密PDF需要提供密码
     *
     * @param file 上传的PDF文件
     * @return 提取的纯文本内容
     * @throws IOException 文件读取异常
     */
    private String extractTextFromPDF(MultipartFile file) throws IOException {
        try (InputStream inputStream = file.getInputStream();
             PDDocument document = PDDocument.load(inputStream)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(document.getNumberOfPages());
            String text = stripper.getText(document);
            log.debug("📄 PDF页数: {}, 提取字符数: {}",
                    document.getNumberOfPages(), text != null ? text.length() : 0);
            return text;
        } catch (IOException e) {
            log.error("❌ PDF解析失败: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 将长文本按段落和字符数切分成小块
     * <p>
     * 切分策略：
     * 1. 优先按段落（\n\n）分割，保持语义完整性
     * 2. 单个段落超过 chunkSize 时，强制按字符切割
     * 3. 每块控制在 chunkSize 字符以内
     * <p>
     * 为什么选择 500 字符？
     * - 过短：语义不完整，检索准确率低
     * - 过长：向量粒度太粗，检索精度下降
     * - 500 是中英文混合场景的常用经验值
     *
     * @param text      原始文本
     * @param chunkSize 每块最大字符数
     * @return 文本片段列表
     */
    private List<String> splitIntoChunks(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();

        // 按双换行分割段落（保留段落结构）
        String[] paragraphs = text.split("\n\n");

        StringBuilder currentChunk = new StringBuilder();

        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            // 处理超长段落：直接按字符切分
            if (trimmed.length() > chunkSize) {
                // 先保存当前已积累的 chunk
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk = new StringBuilder();
                }
                // 按固定大小切分长段落
                for (int i = 0; i < trimmed.length(); i += chunkSize) {
                    int end = Math.min(i + chunkSize, trimmed.length());
                    chunks.add(trimmed.substring(i, end));
                }
                continue;
            }

            // 正常情况：追加段落到当前 chunk
            if (currentChunk.length() + trimmed.length() + 2 > chunkSize) {
                // 当前块已满，保存并创建新块
                chunks.add(currentChunk.toString().trim());
                currentChunk = new StringBuilder();
            }

            // 追加段落（段落间用两个换行分隔）
            if (currentChunk.length() > 0) {
                currentChunk.append("\n\n");
            }
            currentChunk.append(trimmed);
        }

        // 保存最后一块
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    /**
     * 构建 Spring AI Document 对象列表
     * <p>
     * Document 是 Spring AI 的统一文档抽象：
     * - content: 文本内容
     * - metadata: 元数据（来源、索引等），用于检索时追溯
     *
     * @param chunks   文本片段列表
     * @param fileName 原始文件名
     * @return Document 列表
     */
    private List<Document> buildDocuments(List<String> chunks, String fileName) {
        List<Document> documents = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            // 构建元数据
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("source", fileName != null ? fileName : "unknown");
            metadata.put("chunk_index", i);
            metadata.put("total_chunks", chunks.size());

            // 创建 Document 对象
            Document doc = new Document(chunks.get(i), metadata);
            documents.add(doc);
        }

        return documents;
    }
}
