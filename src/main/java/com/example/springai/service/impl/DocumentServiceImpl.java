package com.example.springai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.springai.dto.DocumentListDTO;
import com.example.springai.dto.DocumentUploadDTO;
import com.example.springai.entity.KbDocument;
import com.example.springai.entity.KbDocumentLog;
import com.example.springai.entity.SysDepartment;
import com.example.springai.entity.SysUser;
import com.example.springai.mapper.KbDocumentLogMapper;
import com.example.springai.mapper.KbDocumentMapper;
import com.example.springai.mapper.SysDepartmentMapper;
import com.example.springai.mapper.SysUserMapper;
import com.example.springai.service.DocumentServiceI;
import com.example.springai.service.OcrServiceI;
import com.example.springai.service.WordDocumentServiceI;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DocumentServiceImpl implements DocumentServiceI {

    private static final int CHUNK_SIZE = 800;
    private static final int BATCH_SIZE = 10;
    private static final int PARALLEL_BATCHES = 1;
    private static final int MAX_FILE_SIZE = 50 * 1024 * 1024; // 50MB

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private OcrServiceI ocrServiceI;

    @Autowired
    private WordDocumentServiceI wordDocumentServiceI;

    @Autowired
    private QdrantClient qdrantClient;

    @Autowired
    private KbDocumentMapper documentMapper;

    @Autowired
    private KbDocumentLogMapper documentLogMapper;

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private SysDepartmentMapper departmentMapper;

    @jakarta.annotation.Resource
    private RestTemplate restTemplate;

    @Value("${spring.ai.vectorstore.qdrant.collection-name:purchase_docs}")
    private String collectionName;

    @Value("${file.upload-dir:./uploads}")
    private String uploadDir;

    private Executor executor;

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(Paths.get(uploadDir));
        } catch (IOException e) {
            log.error("创建上传目录失败: {}", e.getMessage());
        }

        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(PARALLEL_BATCHES);
        taskExecutor.setMaxPoolSize(PARALLEL_BATCHES);
        taskExecutor.setQueueCapacity(100);
        taskExecutor.setThreadNamePrefix("vector-");
        taskExecutor.initialize();
        this.executor = taskExecutor;
        log.info("🔧 并行处理线程池初始化完成，核心线程数: {}", PARALLEL_BATCHES);

        ensureCollectionExists();
    }

    // ==================== 核心处理方法（支持 documentId） ====================

    /**
     * 原有 processDocument，兼容旧调用
     */
    @Override
    public int processDocument(MultipartFile file) throws IOException {
        return processDocument(file, null);
    }

    /**
     * 新增重载：处理文档并关联 documentId（用于删除时定位向量）
     */
    public int processDocument(MultipartFile file, Long documentId) throws IOException {
        String fileName = file.getOriginalFilename();
        log.info("📄 开始处理文档: {}, documentId: {}", fileName, documentId);
        long startTime = System.currentTimeMillis();

        String fullText = extractText(file);
        if (fullText == null || fullText.trim().isEmpty()) {
            throw new IOException("文档内容为空或无法提取文本");
        }

        List<String> chunks = splitIntoChunks(fullText, CHUNK_SIZE);
        log.info("✂️ 文本切分完成，共 {} 个片段", chunks.size());

        List<Document> documents = buildDocuments(chunks, fileName, documentId);
        log.info("📦 构建 Document 对象完成，共 {} 个", documents.size());

        int total = documents.size();
        if (total == 0) return 0;

        List<List<Document>> batches = new ArrayList<>();
        for (int i = 0; i < total; i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, total);
            batches.add(documents.subList(i, end));
        }
        log.info("📦 分为 {} 批，每批 {} 个文档", batches.size(), BATCH_SIZE);

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < batches.size(); i++) {
            final int batchIndex = i;
            List<Document> batch = batches.get(i);
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    vectorStore.add(batch);
                } catch (Exception e) {
                    log.error("❌ 第 {}/{} 批处理失败: {}", batchIndex + 1, batches.size(), e.getMessage(), e);
                    throw new RuntimeException(e);
                }
            }, executor);
            futures.add(future);
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        long endTime = System.currentTimeMillis();
        log.info("✅ 文档处理完成: {}，共 {} 个片段，耗时 {}ms", fileName, total, endTime - startTime);
        return total;
    }

    // ==================== 上传 MultipartFile ====================
    @Override
    @Transactional
    public KbDocument uploadDocument(MultipartFile file, DocumentUploadDTO metadata, Long currentUserId) throws IOException {
        String fileName = file.getOriginalFilename();
        log.info("📤 上传文档: {}, 用户: {}", fileName, currentUserId);

        // 1. 校验
        if (file.isEmpty()) throw new IOException("文件为空");
        if (!isSupportedFileType(fileName)) throw new IOException("不支持的文件类型: " + fileName);
        if (file.getSize() > MAX_FILE_SIZE) throw new IOException("文件超过50MB限制");

        // 2. 保存文件
        String savedPath = saveFile(file);

        // 3. 先插入文档记录（获取自增ID）
        KbDocument doc = new KbDocument();
        doc.setTitle(StringUtils.hasText(metadata.getTitle()) ? metadata.getTitle() : fileName);
        doc.setFileName(fileName);
        doc.setFilePath(savedPath);
        doc.setFileSize(file.getSize());
        doc.setFileType(getFileExtension(fileName));
        doc.setUploaderId(currentUserId);
        doc.setDepartmentId(metadata.getDepartmentId());
        doc.setVisibleType(metadata.getVisibleType() != null ? metadata.getVisibleType() : 1);
        doc.setIsPublic(metadata.getIsPublic() != null && metadata.getIsPublic() ? 1 : 0);
        doc.setStatus(0); // 待处理
        doc.setChunkCount(0);
        doc.setViewCount(0);
        doc.setCreatedAt(LocalDateTime.now());
        doc.setUpdatedAt(LocalDateTime.now());
        documentMapper.insert(doc);  // 此时 doc.getId() 已赋值

        // 4. 处理向量化（传入文档ID）
        int chunkCount = processDocument(file, doc.getId(), doc);
        doc.setChunkCount(chunkCount);
        doc.setStatus(1);
        documentMapper.updateById(doc);

        logDocumentAction(doc.getId(), currentUserId, "UPLOAD");
        log.info("✅ 文档上传成功: {}", doc.getTitle());
        return doc;
    }

    // ==================== 从 URL 上传 ====================
    @Override
    @Transactional
    public KbDocument uploadFromUrl(String url, DocumentUploadDTO metadata, Long currentUserId) throws IOException {
        log.info("📤 从URL上传: {}, 用户: {}", url, currentUserId);

        DownloadResult result = downloadFile(url);
        if (result == null) throw new IOException("无法下载文件: " + url);

        // 处理文件名
        String fileName = result.getFileName();
        if (fileName == null || fileName.isEmpty() || "downloaded_file".equals(fileName)) {
            String title = metadata.getTitle();
            if (title != null && !title.isEmpty()) {
                String ext = "";
                try {
                    String path = URI.create(url).getPath();
                    if (path.contains(".")) {
                        ext = path.substring(path.lastIndexOf("."));
                    }
                } catch (Exception ignored) {
                }
                if (ext.isEmpty()) {
                    ext = ".pdf";
                }
                fileName = title + ext;
            } else {
                if (!fileName.contains(".")) {
                    fileName += ".pdf";
                }
            }
        }

        // 校验文件头
        byte[] content = result.getContent();
        if (content.length < 4) {
            throw new IOException("下载的文件内容不足，可能已损坏");
        }
        String header = new String(content, 0, Math.min(4, content.length), StandardCharsets.UTF_8);
        if (fileName.toLowerCase().endsWith(".pdf") && !header.startsWith("%PDF")) {
            throw new IOException("下载的文件不是有效的PDF格式");
        }

        // 保存文件
        String savedPath = saveFile(content, fileName);

        // 构造 MultipartFile
        MultipartFile multipartFile = new ByteArrayMultipartFile(content, fileName, fileName);

        // 先插入文档记录
        KbDocument doc = new KbDocument();
        doc.setTitle(StringUtils.hasText(metadata.getTitle()) ? metadata.getTitle() : fileName);
        doc.setFileName(fileName);
        doc.setFilePath(savedPath);
        doc.setFileSize((long) content.length);
        doc.setFileType(getFileExtension(fileName));
        doc.setUploaderId(currentUserId);
        doc.setDepartmentId(metadata.getDepartmentId());
        doc.setVisibleType(metadata.getVisibleType() != null ? metadata.getVisibleType() : 1);
        doc.setIsPublic(metadata.getIsPublic() != null && metadata.getIsPublic() ? 1 : 0);
        doc.setStatus(0);
        doc.setChunkCount(0);
        doc.setViewCount(0);
        doc.setCreatedAt(LocalDateTime.now());
        doc.setUpdatedAt(LocalDateTime.now());
        documentMapper.insert(doc);

        // 向量化
        int chunkCount = processDocument(multipartFile, doc.getId(), doc);
        doc.setChunkCount(chunkCount);
        doc.setStatus(1);
        documentMapper.updateById(doc);

        logDocumentAction(doc.getId(), currentUserId, "UPLOAD_URL");
        log.info("✅ URL上传成功: {}", doc.getTitle());
        return doc;
    }

    // ==================== 文档列表 ====================
    @Override
    public Page<DocumentListDTO> listDocuments(int page, int size, String keyword, Long departmentId, Long currentUserId) {
        SysUser user = userMapper.selectById(currentUserId);
        if (user == null) throw new RuntimeException("用户不存在");

        QueryWrapper<KbDocument> wrapper = new QueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like("title", keyword).or().like("file_name", keyword));
        }
        if (departmentId != null) {
            wrapper.eq("department_id", departmentId);
        }

        // 权限过滤
        if (user.getIsAdmin() != 1) {
            wrapper.and(w -> w
                    .eq("uploader_id", currentUserId)
                    .or().eq("department_id", user.getDepartmentId())
                    .or().eq("is_public", 1)
            );
        }
        wrapper.orderByDesc("created_at");

        Page<KbDocument> pageParam = new Page<>(page, size);
        Page<KbDocument> docPage = documentMapper.selectPage(pageParam, wrapper);

        Page<DocumentListDTO> dtoPage = new Page<>(docPage.getCurrent(), docPage.getSize(), docPage.getTotal());
        List<DocumentListDTO> dtoList = docPage.getRecords().stream().map(doc -> {
            DocumentListDTO dto = new DocumentListDTO();
            BeanUtils.copyProperties(doc, dto);
            // 上传人姓名
            SysUser uploader = userMapper.selectById(doc.getUploaderId());
            if (uploader != null) dto.setUploaderName(uploader.getRealName());
            // 部门名称
            SysDepartment dept = departmentMapper.selectById(doc.getDepartmentId());
            if (dept != null) dto.setDepartmentName(dept.getDeptName());
            // 可见性文本
            String visibleText;
            switch (doc.getVisibleType()) {
                case 1:
                    visibleText = "本部门";
                    break;
                case 2:
                    visibleText = "全公司";
                    break;
                case 3:
                    visibleText = "指定部门";
                    break;
                default:
                    visibleText = "未知";
            }
            dto.setVisibleText(visibleText);
            return dto;
        }).collect(Collectors.toList());
        dtoPage.setRecords(dtoList);
        return dtoPage;
    }

    // ==================== 删除文档（联动删除向量） ====================
    @Override
    @Transactional
    public void deleteDocument(Long docId, Long currentUserId) {
        KbDocument doc = documentMapper.selectById(docId);
        if (doc == null) throw new RuntimeException("文档不存在");

        SysUser user = userMapper.selectById(currentUserId);
        if (user.getIsAdmin() != 1 && !doc.getUploaderId().equals(currentUserId)) {
            throw new RuntimeException("无权限删除此文档");
        }

        // 1. 删除 Qdrant 向量（按 document_id 过滤）
        deleteVectorsByDocumentId(docId);

        // 2. 删除物理文件
        try {
            Files.deleteIfExists(Paths.get(doc.getFilePath()));
        } catch (IOException e) {
            log.warn("删除物理文件失败: {}", e.getMessage());
        }

        // 3. 删除数据库记录
        documentMapper.deleteById(docId);
        logDocumentAction(docId, currentUserId, "DELETE");
        log.info("文档删除成功: {}", doc.getTitle());
    }

    // ==================== 下载文档 ====================
    @Override
    public Resource downloadDocument(Long docId, Long currentUserId) {
        KbDocument doc = documentMapper.selectById(docId);
        if (doc == null) throw new RuntimeException("文档不存在");

        if (!hasPermission(doc, currentUserId)) {
            throw new RuntimeException("无权限下载此文档");
        }

        File file = new File(doc.getFilePath());
        if (!file.exists()) throw new RuntimeException("文件不存在");

        doc.setViewCount(doc.getViewCount() + 1);
        documentMapper.updateById(doc);
        logDocumentAction(docId, currentUserId, "DOWNLOAD");

        return new FileSystemResource(file);
    }

    // ==================== 获取文档详情 ====================
    @Override
    public KbDocument getDocumentById(Long docId) {
        return documentMapper.selectById(docId);
    }

    // ==================== 辅助方法 ====================

    /**
     * 从 Qdrant 中删除指定文档 ID 的所有向量
     */
    private void deleteVectorsByDocumentId(Long documentId) {
        try {
            // 构建 FieldCondition
            Common.FieldCondition fieldCondition = Common.FieldCondition.newBuilder()
                    .setKey("document_id")
                    .setMatch(Common.Match.newBuilder()
                            .setKeyword(documentId.toString())  // 改为字符串匹配
                            .build())
                    .build();

            Common.Condition condition = Common.Condition.newBuilder()
                    .setField(fieldCondition)
                    .build();

            Common.Filter filter = Common.Filter.newBuilder()
                    .addMust(condition)
                    .build();

            // 先查询匹配数量
            qdrantClient.deleteAsync(collectionName, filter).get();
            log.info("✅ 已从 Qdrant 删除文档 {}", documentId);
        } catch (Exception e) {
            log.error("❌ 从 Qdrant 删除向量失败: {}", e.getMessage(), e);
        }
    }

    private boolean isSupportedFileType(String fileName) {
        String ext = getFileExtension(fileName).toLowerCase();
        return Arrays.asList("pdf", "doc", "docx", "txt", "md").contains(ext);
    }

    private String getFileExtension(String fileName) {
        if (fileName == null) return "";
        int idx = fileName.lastIndexOf(".");
        return idx > 0 ? fileName.substring(idx + 1) : "";
    }

    private String saveFile(MultipartFile file) throws IOException {
        String fileName = file.getOriginalFilename();
        String uniqueName = UUID.randomUUID().toString() + "_" + fileName;
        Path targetPath = Paths.get(uploadDir, uniqueName);
        Files.copy(file.getInputStream(), targetPath);
        return targetPath.toString();
    }

    private String saveFile(InputStream inputStream, String fileName) throws IOException {
        String uniqueName = UUID.randomUUID().toString() + "_" + fileName;
        Path targetPath = Paths.get(uploadDir, uniqueName);
        Files.copy(inputStream, targetPath);
        return targetPath.toString();
    }

    private String saveFile(byte[] content, String fileName) throws IOException {
        String uniqueName = UUID.randomUUID().toString() + "_" + fileName;
        Path targetPath = Paths.get(uploadDir, uniqueName);
        Files.write(targetPath, content);
        return targetPath.toString();
    }

    private DownloadResult downloadFile(String url) throws IOException {
        if (!isValidUrl(url)) throw new IOException("无效的URL");

        HttpURLConnection connection = null;
        try {
            URL downloadUrl = new URL(url);
            connection = (HttpURLConnection) downloadUrl.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");

            int statusCode = connection.getResponseCode();
            if (statusCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("下载失败，HTTP状态码: " + statusCode);
            }

            long fileSize = connection.getContentLengthLong();
            if (fileSize > MAX_FILE_SIZE) {
                throw new IOException("文件超过50MB限制");
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (InputStream is = connection.getInputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    baos.write(buffer, 0, bytesRead);
                }
            }
            byte[] content = baos.toByteArray();

            String fileName = extractFileNameFromHeaders(connection);
            if (fileName == null) {
                String path = new URI(url).getPath();
                fileName = path.substring(path.lastIndexOf("/") + 1);
                if (fileName.isEmpty()) {
                    fileName = "downloaded_file";
                }
            }

            return new DownloadResult(fileName, content);

        } catch (Exception e) {
            if (connection != null) {
                connection.disconnect();
            }
            throw new IOException("下载失败: " + e.getMessage(), e);
        }
    }

    private String extractFileNameFromResponse(ResponseEntity<?> response, String url) {
        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        if (StringUtils.hasText(disposition)) {
            String[] parts = disposition.split(";");
            for (String part : parts) {
                if (part.trim().startsWith("filename=")) {
                    String name = part.trim().substring(9);
                    if (name.startsWith("\"") && name.endsWith("\"")) {
                        name = name.substring(1, name.length() - 1);
                    }
                    return name;
                }
            }
        }
        try {
            String path = URI.create(url).getPath();
            String name = path.substring(path.lastIndexOf("/") + 1);
            if (StringUtils.hasText(name)) return name;
        } catch (Exception ignored) {
        }
        return "downloaded_file";
    }

    private boolean isValidUrl(String url) {
        try {
            URI.create(url).toURL();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasPermission(KbDocument doc, Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user.getIsAdmin() == 1) return true;
        if (doc.getIsPublic() == 1) return true;
        if (doc.getUploaderId().equals(userId)) return true;
        if (doc.getDepartmentId() != null && doc.getDepartmentId().equals(user.getDepartmentId())) {
            return doc.getVisibleType() == 1;
        }
        return false;
    }

    private void logDocumentAction(Long docId, Long userId, String action) {
        KbDocumentLog log = new KbDocumentLog();
        log.setDocumentId(docId);
        log.setUserId(userId);
        log.setAction(action);
        log.setCreatedAt(LocalDateTime.now());
        documentLogMapper.insert(log);
    }

    // ==================== 原有辅助方法 ====================
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
        } else if (lowerName.endsWith(".txt") || lowerName.endsWith(".md")) {
            try (InputStream inputStream = file.getInputStream()) {
                return new String(inputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
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
        } catch (IOException e) {
            log.error("PDF解析失败: {}", e.getMessage());
            throw e;
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

    /**
     * 构建 Document 对象，支持传入 documentId
     */
    private List<Document> buildDocuments(List<String> chunks, String fileName, Long documentId) {
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("source", fileName != null ? fileName : "unknown");
            metadata.put("chunk_index", i);
            metadata.put("total_chunks", chunks.size());
            if (documentId != null) {
                metadata.put("document_id", documentId);  // 关键：存储文档ID
            }
            documents.add(new Document(chunks.get(i), metadata));
        }
        return documents;
    }

    private void ensureCollectionExists() {
        try {
            boolean exists = qdrantClient.collectionExistsAsync(collectionName).get();
            if (!exists) {
                log.info("📦 集合 {} 不存在，正在创建...", collectionName);
                io.qdrant.client.grpc.Collections.VectorParams vectorParams =
                        io.qdrant.client.grpc.Collections.VectorParams.newBuilder()
                                .setSize(768)   // nomic-embed-text 向量维度
                                .setDistance(io.qdrant.client.grpc.Collections.Distance.Cosine)
                                .build();
                qdrantClient.createCollectionAsync(collectionName, vectorParams).get();
                log.info("✅ 集合 {} 创建成功", collectionName);
            }
        } catch (Exception e) {
            log.error("❌ 检查/创建集合失败: {}", e.getMessage(), e);
            throw new RuntimeException("Qdrant 集合初始化失败", e);
        }
    }

    // ==================== 内部类 ====================
    private static class DownloadResult {
        private final String fileName;
        private final byte[] content;
        private final long fileSize;

        public DownloadResult(String fileName, byte[] content) {
            this.fileName = fileName;
            this.content = content;
            this.fileSize = content.length;
        }

        public String getFileName() {
            return fileName;
        }

        public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
        }

        public long getFileSize() {
            return fileSize;
        }

        public byte[] getContent() {
            return content;
        }
    }

    private static class ByteArrayMultipartFile implements MultipartFile {
        private final byte[] content;
        private final String name;
        private final String originalFilename;

        public ByteArrayMultipartFile(byte[] content, String name, String originalFilename) {
            this.content = content;
            this.name = name;
            this.originalFilename = originalFilename;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getOriginalFilename() {
            return originalFilename;
        }

        @Override
        public String getContentType() {
            return null;
        }

        @Override
        public boolean isEmpty() {
            return content == null || content.length == 0;
        }

        @Override
        public long getSize() {
            return content.length;
        }

        @Override
        public byte[] getBytes() throws IOException {
            return content;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return new ByteArrayInputStream(content);
        }

        @Override
        public void transferTo(File dest) throws IOException, IllegalStateException {
            Files.write(dest.toPath(), content);
        }
    }

    private List<Document> buildDocuments(List<String> chunks, String fileName, Long documentId, KbDocument doc) {
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("source", fileName != null ? fileName : "unknown");
            metadata.put("chunk_index", i);
            metadata.put("total_chunks", chunks.size());
            metadata.put("document_id", documentId != null ? documentId : 0);
            // 关键：存入部门ID
            if (doc.getDepartmentId() != null) {
                metadata.put("department_id", doc.getDepartmentId().toString());
            }
            // 存入是否公开
            metadata.put("is_public", doc.getIsPublic() != null ? doc.getIsPublic().toString() : "0");
            documents.add(new Document(chunks.get(i), metadata));
        }
        return documents;
    }

    public int processDocument(MultipartFile file, Long documentId, KbDocument doc) throws IOException {
        String fileName = file.getOriginalFilename();
        log.info("📄 开始处理文档: {}, documentId: {}", fileName, documentId);
        long startTime = System.currentTimeMillis();

        String fullText = extractText(file);
        if (fullText == null || fullText.trim().isEmpty()) {
            throw new IOException("文档内容为空或无法提取文本");
        }

        List<String> chunks = splitIntoChunks(fullText, CHUNK_SIZE);
        log.info("✂️ 文本切分完成，共 {} 个片段", chunks.size());

        List<Document> documents = buildDocuments(chunks, fileName, documentId, doc);
        log.info("📦 构建 Document 对象完成，共 {} 个", documents.size());

        int total = documents.size();
        if (total == 0) return 0;

        List<List<Document>> batches = new ArrayList<>();
        for (int i = 0; i < total; i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, total);
            batches.add(documents.subList(i, end));
        }
        log.info("📦 分为 {} 批，每批 {} 个文档", batches.size(), BATCH_SIZE);

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < batches.size(); i++) {
            final int batchIndex = i;
            List<Document> batch = batches.get(i);
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    vectorStore.add(batch);
                } catch (Exception e) {
                    log.error("❌ 第 {}/{} 批处理失败: {}", batchIndex + 1, batches.size(), e.getMessage(), e);
                    throw new RuntimeException(e);
                }
            }, executor);
            futures.add(future);
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        long endTime = System.currentTimeMillis();
        log.info("✅ 文档处理完成: {}，共 {} 个片段，耗时 {}ms", fileName, total, endTime - startTime);
        return total;
    }


    private String extractFileNameFromHeaders(HttpURLConnection connection) {
        String disposition = connection.getHeaderField("Content-Disposition");
        if (disposition != null && !disposition.isEmpty()) {
            // 解析 filename=xxx 或 filename="xxx"
            Pattern pattern = Pattern.compile("filename=\"?([^\";]+)\"?");
            Matcher matcher = pattern.matcher(disposition);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }
}