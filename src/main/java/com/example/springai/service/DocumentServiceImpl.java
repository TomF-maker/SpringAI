package com.example.springai.service;

import com.google.protobuf.NullValue;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DocumentServiceImpl implements DocumentServiceI {

    private static final int CHUNK_SIZE = 800;
    private static final int BATCH_SIZE = 10;           // 每批处理的文档数
    private static final int PARALLEL_BATCHES = 1;       // 并行批次数（可根据需要调整）

    @Autowired
    private VectorStore vectorStore;   // 保留，但后续不再使用

    @Autowired
    private EmbeddingModel embeddingModel;   // Spring AI 的嵌入模型（作为回退）

    @Autowired
    private OcrServiceI ocrServiceI;

    @Autowired
    private WordDocumentServiceI wordDocumentServiceI;

    @Autowired
    private QdrantClient qdrantClient;

    @Value("${spring.ai.vectorstore.qdrant.collection-name:purchase_docs}")
    private String collectionName;

    @Value("${spring.ai.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${spring.ai.ollama.embedding.model:nomic-embed-text}")
    private String embeddingModelName;

    private Executor executor;
    private WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        // 自定义线程池
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(PARALLEL_BATCHES);
        taskExecutor.setMaxPoolSize(PARALLEL_BATCHES);
        taskExecutor.setQueueCapacity(100);
        taskExecutor.setThreadNamePrefix("vector-");
        taskExecutor.initialize();
        this.executor = taskExecutor;
        log.info("🔧 并行处理线程池初始化完成，核心线程数: {}", PARALLEL_BATCHES);

        // 初始化 WebClient（用于调用 Ollama API）
        this.webClient = WebClient.create(ollamaBaseUrl);
        log.info("🔧 Ollama WebClient 初始化完成，baseUrl: {}", ollamaBaseUrl);
    }

    /**
     * 通过 Ollama 的 /api/embed 接口批量获取文本向量
     * @param texts 文本列表
     * @return 向量列表 (float[][])
     */
    private List<float[]> batchEmbedWithOllama(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }

        // 构建请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", embeddingModelName);
        requestBody.put("input", texts);

        try {
            log.debug("📤 调用 Ollama 批量嵌入，文本数量: {}", texts.size());
            long start = System.currentTimeMillis();

            // 发送 POST 请求
            String responseJson = webClient.post()
                    .uri("/api/embed")
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();  // 同步等待

            long elapsed = System.currentTimeMillis() - start;
            log.debug("⏱️ Ollama 批量嵌入耗时: {}ms", elapsed);

            // 解析响应
            JsonNode root = objectMapper.readTree(responseJson);
            JsonNode embeddingsNode = root.get("embeddings");
            if (embeddingsNode == null || !embeddingsNode.isArray()) {
                throw new RuntimeException("Ollama 返回的 embeddings 为空或格式错误");
            }

            List<float[]> result = new ArrayList<>();
            for (JsonNode embNode : embeddingsNode) {
                if (!embNode.isArray()) continue;
                float[] vector = new float[embNode.size()];
                for (int i = 0; i < embNode.size(); i++) {
                    vector[i] = (float) embNode.get(i).asDouble();
                }
                result.add(vector);
            }

            if (result.size() != texts.size()) {
                log.warn("⚠️ 返回的向量数量 ({}) 与请求文本数量 ({}) 不一致", result.size(), texts.size());
            }
            return result;

        } catch (Exception e) {
            log.error("❌ Ollama 批量嵌入失败，将回退到 Spring AI 逐个嵌入: {}", e.getMessage());
            // 回退：逐个调用 Spring AI 的 embed 方法
            return texts.stream()
                    .map(embeddingModel::embed)
                    .collect(Collectors.toList());
        }
    }

    /**
     * 将普通 Map<String, Object> 转换为 Qdrant 1.19.0 所需的 JsonWithInt.Value
     */
    private Map<String, JsonWithInt.Value> convertToJsonWithIntValue(Map<String, Object> map) {
        Map<String, JsonWithInt.Value> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();
            JsonWithInt.Value.Builder builder = JsonWithInt.Value.newBuilder();

            if (val == null) {
                builder.setNullValue(JsonWithInt.NullValue.NULL_VALUE);
            } else if (val instanceof String) {
                builder.setStringValue((String) val);
            } else if (val instanceof Number) {
                // 使用 setNumberValue 兼容整数和浮点数
                builder.setDoubleValue(((Number) val).doubleValue());
            } else if (val instanceof Boolean) {
                builder.setBoolValue((Boolean) val);
            } else {
                // 其他类型（如 List、Map）转成字符串
                builder.setStringValue(val.toString());
            }
            result.put(key, builder.build());
        }
        return result;
    }

    /**
     * 处理单个批次：批量嵌入 + 直接写入 Qdrant
     */
    private void processBatchWithBulkEmbedding(List<Document> batch, int batchIndex, int totalBatches) {
        if (batch == null || batch.isEmpty()) return;

        log.info("🚀 开始处理第 {}/{} 批 ({} 个文档)", batchIndex + 1, totalBatches, batch.size());
        long batchStart = System.currentTimeMillis();

        try {
            // 1. 提取所有文本
            List<String> texts = batch.stream()
                    .map(Document::getFormattedContent)
                    .collect(Collectors.toList());

            // 2. 批量嵌入（优先使用 Ollama 直接调用，失败则回退到 Spring AI）
            List<float[]> embeddings;
            try {
                embeddings = batchEmbedWithOllama(texts);
            } catch (Exception e) {
                log.warn("⚠️ 批量嵌入异常，回退到 Spring AI 逐个嵌入: {}", e.getMessage());
                embeddings = texts.stream()
                        .map(embeddingModel::embed)
                        .collect(Collectors.toList());
            }

            if (embeddings.size() != texts.size()) {
                throw new RuntimeException("嵌入向量数量与文本数量不一致");
            }
            log.debug("✅ 批量嵌入成功，共 {} 个向量", embeddings.size());

            // 3. 构建 Qdrant Point 列表
            List<Points.PointStruct> points = new ArrayList<>();
            for (int i = 0; i < batch.size(); i++) {
                Document doc = batch.get(i);
                float[] vectorArray = embeddings.get(i);

                // 转换向量为 List<Float>
                List<Float> vectorFloats = new ArrayList<>(vectorArray.length);
                for (float v : vectorArray) {
                    vectorFloats.add(v);
                }

                // 构建 Qdrant Vector
                Points.Vector qdrantVector = Points.Vector.newBuilder()
                        .addAllData(vectorFloats)
                        .build();

                // 构建 Vectors（1.19.0 要求用 Vectors 包装）
                Points.Vectors vectors = Points.Vectors.newBuilder()
                        .setVector(qdrantVector)
                        .build();

                // 构建 payload（元数据 + content）
                Map<String, Object> rawPayload = new HashMap<>(doc.getMetadata());
                rawPayload.put("content", doc.getFormattedContent());

                // 转换为 JsonWithInt.Value
                Map<String, JsonWithInt.Value> payload = convertToJsonWithIntValue(rawPayload);

                // PointId
                Common.PointId pointId = Common.PointId.newBuilder()
                        .setUuid(UUID.randomUUID().toString())
                        .build();

                // PointStruct
                Points.PointStruct point = Points.PointStruct.newBuilder()
                        .setId(pointId)
                        .setVectors(vectors)
                        .putAllPayload(payload)
                        .build();

                points.add(point);
            }

            // 4. 批量 Upsert
            qdrantClient.upsertAsync(collectionName, points).get();

            long batchEnd = System.currentTimeMillis();
            log.info("✅ 第 {}/{} 批完成，耗时: {}ms", batchIndex + 1, totalBatches, batchEnd - batchStart);

        } catch (Exception e) {
            log.error("❌ 第 {}/{} 批处理失败: {}", batchIndex + 1, totalBatches, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    // ==================== 以下为原有辅助方法，保持不变 ====================

    @Override
    public int processDocument(MultipartFile file) throws IOException {
        String fileName = file.getOriginalFilename();
        log.info("📄 开始处理文档: {}", fileName);
        long startTime = System.currentTimeMillis();

        // 1. 提取文本
        String fullText = extractText(file);
        if (fullText == null || fullText.trim().isEmpty()) {
            throw new IOException("文档内容为空或无法提取文本");
        }

        // 2. 切分
        List<String> chunks = splitIntoChunks(fullText, CHUNK_SIZE);
        log.info("✂️ 文本切分完成，共 {} 个片段", chunks.size());

        // 3. 构建 Document 对象（用于元数据）
        List<Document> documents = buildDocuments(chunks, fileName);
        log.info("📦 构建 Document 对象完成，共 {} 个", documents.size());

        // 4. 并行批量向量化存储
        int total = documents.size();
        if (total == 0) return 0;

        // 拆分成批次
        List<List<Document>> batches = new ArrayList<>();
        for (int i = 0; i < total; i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, total);
            batches.add(documents.subList(i, end));
        }
        log.info("📦 分为 {} 批，每批 {} 个文档", batches.size(), BATCH_SIZE);

        // 并行处理各批次
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < batches.size(); i++) {
            final int batchIndex = i;
            List<Document> batch = batches.get(i);
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    processBatchWithBulkEmbedding(batch, batchIndex, batches.size());
                } catch (Exception e) {
                    log.error("❌ 第 {}/{} 批处理失败: {}", batchIndex + 1, batches.size(), e.getMessage(), e);
                    throw new RuntimeException(e);
                }
            }, executor);
            futures.add(future);
        }

        // 等待所有批次完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        long endTime = System.currentTimeMillis();
        log.info("✅ 文档处理完成: {}，共 {} 个片段，耗时 {}ms", fileName, total, endTime - startTime);
        return total;
    }

    private String extractText(MultipartFile file) throws IOException {
        String fileName = file.getOriginalFilename();
        if (fileName == null) {
            throw new IOException("文件名无效");
        }
        String lowerName = fileName.toLowerCase();
        if (lowerName.endsWith(".pdf")) {
            return extractTextFromPDF(file);
        } else if (lowerName.endsWith(".docx") || lowerName.endsWith(".doc")) {
            return wordDocumentServiceI.extractText(file);
        } else {
            throw new IOException("不支持的文件格式: " + fileName);
        }
    }

    private String extractTextFromPDF(MultipartFile file) throws IOException {
        StringBuilder fullText = new StringBuilder();
        try (InputStream inputStream = file.getInputStream();
             PDDocument document = PDDocument.load(inputStream)) {
            PDFTextStripper stripper = new PDFTextStripper();
            PDFRenderer renderer = new PDFRenderer(document);
            int totalPages = document.getNumberOfPages();
            log.info("📄 PDF 共 {} 页", totalPages);
            for (int i = 0; i < totalPages; i++) {
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                String pageText = stripper.getText(document).trim();
                if (!pageText.isEmpty()) {
                    fullText.append(pageText).append("\n\n");
                } else {
                    log.info("🔍 第 {} 页：无文本层，使用 OCR", i + 1);
                    try {
                        BufferedImage image = renderer.renderImageWithDPI(i, 300);
                        String ocrText = ocrServiceI.recognizeText(image);
                        if (!ocrText.isEmpty()) {
                            fullText.append(ocrText).append("\n\n");
                            log.info("✅ 第 {} 页：OCR 识别成功", i + 1);
                        }
                    } catch (Exception e) {
                        log.error("OCR 失败: {}", e.getMessage());
                    }
                }
            }
        }
        return fullText.toString();
    }

    private List<String> splitIntoChunks(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        String[] paragraphs = text.split("\n\n");
        StringBuilder current = new StringBuilder();
        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.length() > chunkSize) {
                if (current.length() > 0) {
                    chunks.add(current.toString());
                    current = new StringBuilder();
                }
                for (int i = 0; i < trimmed.length(); i += chunkSize) {
                    int end = Math.min(i + chunkSize, trimmed.length());
                    chunks.add(trimmed.substring(i, end));
                }
            } else if (current.length() + trimmed.length() + 2 > chunkSize) {
                chunks.add(current.toString());
                current = new StringBuilder();
                current.append(trimmed);
            } else {
                if (current.length() > 0) current.append("\n\n");
                current.append(trimmed);
            }
        }
        if (current.length() > 0) chunks.add(current.toString());
        return chunks;
    }

    private List<Document> buildDocuments(List<String> chunks, String fileName) {
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("source", fileName != null ? fileName : "unknown");
            metadata.put("chunk_index", i);
            metadata.put("total_chunks", chunks.size());
            documents.add(new Document(chunks.get(i), metadata));
        }
        return documents;
    }
}