package com.example.springai.service;


import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

    @Autowired
    private OcrServiceI ocrServiceI;
    @Autowired
    private WordDocumentServiceI wordDocumentServiceI;
    @Value("${app.debug.text-output:false}")
    private boolean debugTextOutput;
    // 在 processDocument 方法中，将 documents 分批写入
    private static final int BATCH_SIZE = 100; // 每批100个

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

        String fullText;
        // 1. 提取PDF文本
        if (fileName.toLowerCase().endsWith(".pdf")) {
            // PDF 使用现有的逐页解析（含 OCR）
            log.debug("正在提取PDF文本...");
            fullText = extractTextFromPDF(file);
        } else if (fileName.toLowerCase().endsWith(".docx") || fileName.toLowerCase().endsWith(".doc")) {
            // Word 文档使用 POI 解析
            fullText = wordDocumentServiceI.extractText(file);
            // 如果 Word 内容为空，可以尝试进一步处理（如提取嵌入图片进行 OCR）
            if (fullText == null || fullText.trim().isEmpty()) {
                log.warn("⚠️ Word 文档文本为空，可能包含图片或特殊格式");
                // 可扩展：使用 POI 提取图片后调用 OCR
            }
        } else {
            throw new IOException("不支持的文件格式: " + fileName);
        }

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
        // 分批存入向量库
        int total = documents.size();
        int successCount = 0;
        for (int i = 0; i < total; i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, total);
            List<Document> batch = documents.subList(i, end);
            try {
                vectorStore.add(batch);
                successCount += batch.size();
                log.info("✅ 批次 {}/{} 存入成功 ({} 个)", i / BATCH_SIZE + 1, (total + BATCH_SIZE - 1) / BATCH_SIZE, batch.size());
            } catch (Exception e) {
                log.error("❌ 批次 {}/{} 存入失败: {}", i / BATCH_SIZE + 1, (total + BATCH_SIZE - 1) / BATCH_SIZE, e.getMessage());
                // 可选择重试或抛出异常
                throw new IOException("向量存入失败: " + e.getMessage(), e);
            }
        }

        long endTime = System.currentTimeMillis();
        log.info("✅ 文档处理完成: {}，共 {} 个片段，耗时 {}ms",
                fileName, documents.size(), (endTime - startTime));

        return successCount;
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
    /**
     * 提取 PDF 文本：优先提取文本层，若某页无文本则使用 OCR
     */
    private String extractTextFromPDF(MultipartFile file) throws IOException {
        StringBuilder fullText = new StringBuilder();

        try (InputStream inputStream = file.getInputStream();
             PDDocument document = PDDocument.load(inputStream)) {

            PDFTextStripper stripper = new PDFTextStripper();
            PDFRenderer renderer = new PDFRenderer(document);

            int totalPages = document.getNumberOfPages();
            log.info("📄 PDF 共 {} 页", totalPages);

            for (int i = 0; i < totalPages; i++) {
                // 1. 先尝试提取该页的文本层
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                String pageText = stripper.getText(document).trim();

                if (!pageText.isEmpty()) {
                    // 有文本层，直接使用
                    fullText.append(pageText).append("\n\n");
                    log.debug("📄 第 {} 页：文本层提取成功，共 {} 字符", i + 1, pageText.length());
                } else {
                    // 2. 无文本层，使用 OCR 识别整页图片
                    log.info("🔍 第 {} 页：无文本层，使用 OCR 识别...", i + 1);
                    try {
                        BufferedImage image = renderer.renderImageWithDPI(i, 300);
                        String ocrText = ocrServiceI.recognizeText(image);
                        if (!ocrText.isEmpty()) {
                            fullText.append(ocrText).append("\n\n");
                            log.info("✅ 第 {} 页：OCR 识别成功，共 {} 字符", i + 1, ocrText.length());
                        } else {
                            log.warn("⚠️ 第 {} 页：OCR 识别结果为空", i + 1);
                        }
                    } catch (Exception e) {
                        log.error("❌ 第 {} 页：OCR 识别失败: {}", i + 1, e.getMessage());
                    }
                }
            }
        }

        return fullText.toString();
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
