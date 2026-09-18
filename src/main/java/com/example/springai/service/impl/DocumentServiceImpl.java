package com.example.springai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.springai.common.ErrorCode;
import com.example.springai.dto.DailyUpload;
import com.example.springai.dto.DocumentListDTO;
import com.example.springai.dto.DocumentUploadDTO;
import com.example.springai.dto.StatisticsDTO;
import com.example.springai.entity.KbDocument;
import com.example.springai.entity.KbDocumentLog;
import com.example.springai.entity.SysCompany;
import com.example.springai.entity.SysUser;
import com.example.springai.exception.BizException;
import com.example.springai.mapper.KbDocumentLogMapper;
import com.example.springai.mapper.KbDocumentMapper;
import com.example.springai.mapper.SysCompanyMapper;
import com.example.springai.mapper.SysUserMapper;
import com.example.springai.service.DocumentServiceI;
import com.example.springai.service.ExcelDocumentServiceI;
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

    /**
     * 文档归属部门写入的常量占位。
     *
     * <p>{@code kb_document.department_id} 是 NOT NULL 且没有默认值，必须写一个值；
     * 而部门维度已经废弃（见 doc/商业化方案.md「A2. 去掉部门维度」），
     * 不再按表单/用户取值，一律写这个常量。**不要删列** —— 涉及存量数据与回滚，
     * 彻底删列要等确认没有回滚需求之后。
     */
    private static final Long LEGACY_DEPARTMENT_ID = 1L;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private OcrServiceI ocrServiceI;

    @Autowired
    private WordDocumentServiceI wordDocumentServiceI;

    @Autowired
    private ExcelDocumentServiceI excelDocumentServiceI;

    @Autowired
    private QdrantClient qdrantClient;

    @Autowired
    private KbDocumentMapper documentMapper;

    @Autowired
    private KbDocumentLogMapper documentLogMapper;

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private SysCompanyMapper companyMapper;

    @jakarta.annotation.Resource
    private RestTemplate restTemplate;

    @Value("${spring.ai.vectorstore.qdrant.collection-name:purchase_docs}")
    private String collectionName;

    /** 向量维度：必须与 embedding 模型一致（bge-m3 = 1024），只从配置读，别再写死 */
    @Value("${spring.ai.vectorstore.qdrant.vector-size:1024}")
    private int vectorSize;

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
    // 刻意不加 @Transactional：processDocument 里是几分钟的远程 embedding。
    // 把这段套进事务，JDBC 连接就会空挂着开着的事务直到被 MySQL / 中间设备掐断
    // （178 段就要 11 分钟，公网连接必断），提交和回滚双双失败；
    // 而 Qdrant 的向量本来就不在这个事务里 —— 结果是文档行被隐式回滚掉、
    // 向量留下变成没有主人的孤儿，检索不到还占着库。
    // 现在 insert 立即提交（文档马上以"待处理"出现在列表里，不用等十几分钟），
    // embedding 在事务外跑，成功置 status=1，失败置 status=2，不会静默消失。
    @Override
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
        doc.setDepartmentId(resolveDepartmentId(metadata.getDepartmentId()));
        // 客户公司隔离：null = 通用方法论（所有客户可见）
        doc.setClientId(metadata.getClientId());
        doc.setVisibleType(metadata.getVisibleType() != null ? metadata.getVisibleType() : 1);
        doc.setIsPublic(metadata.getIsPublic() != null && metadata.getIsPublic() ? 1 : 0);
        doc.setStatus(0); // 待处理
        doc.setChunkCount(0);
        doc.setViewCount(0);
        doc.setCreatedAt(LocalDateTime.now());
        doc.setUpdatedAt(LocalDateTime.now());
        documentMapper.insert(doc);  // 此时 doc.getId() 已赋值（已提交，见方法上的说明）

        // 4. 处理向量化（传入文档ID）—— 在事务外，失败要把文档标成"失败"而不是让它挂在"待处理"
        try {
            int chunkCount = processDocument(file, doc.getId(), doc);
            doc.setChunkCount(chunkCount);
            doc.setStatus(1);
            documentMapper.updateById(doc);
        } catch (Exception e) {
            log.error("❌ 文档向量化失败，标记为失败: {}", doc.getTitle(), e);
            try {
                doc.setStatus(2);
                documentMapper.updateById(doc);
            } catch (Exception updateError) {
                // 连标记失败都写不进去，通常意味着数据库连接也断了，
                // 文档会停在 status=0，别把它吞掉
                log.error("❌ 标记文档失败状态也失败了，文档将停留在待处理: {}", doc.getId(), updateError);
            }
            throw e;
        }

        logDocumentAction(doc.getId(), currentUserId, "UPLOAD");
        log.info("✅ 文档上传成功: {}", doc.getTitle());
        return doc;
    }

    @Override
    public KbDocument uploadDocumentBytes(byte[] content, String originalName,
                                          DocumentUploadDTO metadata, Long currentUserId) throws IOException {
        // 只是把字节数组包成 MultipartFile 后转调 uploadDocument ——
        // 校验、入库、向量化、状态标记全部复用同一条路径。
        // **不要在这里另写一套**：那等于把"客户隔离收口"之类的规则复制一份，
        // 两份实现分叉出来的那条就是漏洞。
        return uploadDocument(new ByteArrayMultipartFile(content, originalName, originalName),
                metadata, currentUserId);
    }

    // ==================== 从 URL 上传 ====================
    // 同 uploadDocument：不能把几分钟的 embedding 套进事务，理由见那边的方法注释
    @Override
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
        doc.setDepartmentId(resolveDepartmentId(metadata.getDepartmentId()));
        // 客户公司隔离：null = 通用方法论（所有客户可见）
        doc.setClientId(metadata.getClientId());
        doc.setVisibleType(metadata.getVisibleType() != null ? metadata.getVisibleType() : 1);
        doc.setIsPublic(metadata.getIsPublic() != null && metadata.getIsPublic() ? 1 : 0);
        doc.setStatus(0);
        doc.setChunkCount(0);
        doc.setViewCount(0);
        doc.setCreatedAt(LocalDateTime.now());
        doc.setUpdatedAt(LocalDateTime.now());
        documentMapper.insert(doc);

        // 向量化
        try {
            int chunkCount = processDocument(multipartFile, doc.getId(), doc);
            doc.setChunkCount(chunkCount);
            doc.setStatus(1);
            documentMapper.updateById(doc);
        } catch (Exception e) {
            log.error("❌ 文档向量化失败，标记为失败: {}", doc.getTitle(), e);
            try {
                doc.setStatus(2);
                documentMapper.updateById(doc);
            } catch (Exception updateError) {
                log.error("❌ 标记文档失败状态也失败了，文档将停留在待处理: {}", doc.getId(), updateError);
            }
            throw e;
        }

        logDocumentAction(doc.getId(), currentUserId, "UPLOAD_URL");
        log.info("✅ URL上传成功: {}", doc.getTitle());
        return doc;
    }

    // ==================== 文档列表 ====================
    @Override
    public Page<DocumentListDTO> listDocuments(int page, int size, String keyword, Long clientId, Long currentUserId) {
        SysUser user = userMapper.selectById(currentUserId);
        if (user == null) throw new RuntimeException("用户不存在");

        QueryWrapper<KbDocument> wrapper = new QueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like("title", keyword).or().like("file_name", keyword));
        }

        // 权限过滤 + 客户公司隔离
        //   内部账号（内部管理员 / 咨询顾问）
        //                 → 跨所有客户看全部（可按 clientId 参数筛到某家客户的专属 + 通用）
        //   外部用户（客户公司员工 / 客户管理员）
        //                 → 只能看本公司的全部文档 + 通用的公开文档
        //   （A 客户付费做的方案，B 客户绝不能看到 —— 咨询行业铁律）
        //
        // **必须判 !isExternal() 而不是 isAdmin == 1**：客户公司的管理员用同一个
        // is_admin 标志位，只看这一列他会走进"看所有客户"的分支，直接列出别家的文档。
        //
        // 2026-09-18 统一：内部管理员与咨询顾问原本是两条不同分支（后者只列
        // "本人上传 + 公开"），结果是"问答里引用了某文档、列表里找不到、点下载还 403"。
        // 现在两类内部账号合为一条，与检索侧 internalUserFilter（不过滤）、
        // 下载侧 hasPermission 保持同一口径。
        if (!user.isExternal()) {
            if (clientId != null) {
                wrapper.and(w -> w.isNull("client_id").or().eq("client_id", clientId));
            }
            // clientId == null：内部账号不加限制，看所有客户的文档
        } else {
            // 客户公司员工（外部用户）：client_id 隔离
            // 看自己公司的全部文档 + 通用且公开的文档
            wrapper.and(w -> w
                    .eq("client_id", user.getCompanyId())
                    .or(q -> q.isNull("client_id").eq("is_public", 1))
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
            // 客户公司名（null=通用方法论）
            if (doc.getClientId() != null) {
                SysCompany company = companyMapper.selectById(doc.getClientId());
                if (company != null) dto.setClientName(company.getCompanyName());
            }
            // 归属只有两档：所属客户（clientName）+ 是否公开（isPublic）。
            // 部门名与"可见性文本"（本部门/全公司/指定部门）已随部门维度一起删掉 ——
            // visible_type 列仍在库里，但不再参与任何权限判断，也就不该再显示给用户。
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
        if (!canDeleteDocument(user, doc, currentUserId)) {
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
        return Arrays.asList("pdf", "doc", "docx", "xls", "xlsx", "txt", "md").contains(ext);
    }

    /**
     * 解析文档的归属部门。**现在只是个常量**，不再查 {@code sys_department}。
     *
     * <p>为什么还要有这个方法：{@code kb_document.department_id} 是 NOT NULL 且没有默认值，
     * 必须写进一个值，所以写入端统一填 {@link #LEGACY_DEPARTMENT_ID}。
     * 而部门维度已经废弃（见 doc/商业化方案.md「A2. 去掉部门维度」），
     * 权限判断、检索过滤、界面都不再读它 —— 保留方法名只是为了改动面最小，
     * 调用点不用动；参数已经没有任何作用。
     *
     * <p>原来这里是"取第一个启用中的部门，没有就抛异常"，它把上传链路和部门表绑死了：
     * 部门表一旦被清空或停用，上传会直接失败，而报错跟"选没选部门"毫无关系。
     * 等到确认不需要回滚、可以彻底删掉这一列时，这个方法可以连同列一起删。
     */
    private Long resolveDepartmentId(Long ignoredDepartmentId) {
        return LEGACY_DEPARTMENT_ID;
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

    /**
     * 是否可以删除这份文档。
     *
     * <ul>
     *   <li>内部管理员：可以删任何文档（它管着整个知识库）</li>
     *   <li><b>客户管理员：只能删本公司的专属文档</b>。两个边界都不能少：
     *       必须是本公司（不能删别家的），且 clientId 非 null（
     *       <b>不能删通用文档</b> —— 那是所有客户共用的方法论，
     *       让一家客户删掉会影响其他所有客户）。</li>
     *   <li>其他外部用户：只能删自己上传的</li>
     * </ul>
     */
    private boolean canDeleteDocument(SysUser user, KbDocument doc, Long currentUserId) {
        if (user == null) return false;
        if (user.isInternalAdmin()) return true;
        if (user.isClientAdmin()) {
            return doc.getClientId() != null && doc.getClientId().equals(user.getCompanyId());
        }
        return doc.getUploaderId() != null && doc.getUploaderId().equals(currentUserId);
    }

    @Override
    public KbDocument getDocumentForUser(Long docId, Long currentUserId) {
        KbDocument doc = documentMapper.selectById(docId);
        // 不存在与无权访问返回同一个结果，避免反复试 id 探出哪些文档真实存在
        if (doc == null || !hasPermission(doc, currentUserId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "文档不存在");
        }
        return doc;
    }

    private boolean hasPermission(KbDocument doc, Long userId) {
        SysUser user = userMapper.selectById(userId);

        // 内部账号（内部管理员 / 咨询顾问）一律放行 —— 内部就是"能看所有客户的内容"，
        // 两类账号的区别只在能不能进管理看板，不在文档可见范围。
        // 这条与检索侧（RagServiceImpl.internalUserFilter 不过滤）、列表侧
        // （listDocuments 内部分支不加限制）保持同一口径。
        //
        // 2026-09-18 统一：这里原本只放行"公开 + 本人上传"，于是会出现
        // "问答里引用了某份客户文档、列表也能看到、点下载却 403"。
        // 当时不敢放开的原因是 AuthController.register 建号写的是 userType=1
        // （放开等于让自助注册的账号下载所有客户的文档）；**该根因已修**
        // （register 改为 userType=2，xlsx 导入本来也是 2），所以三处口径可以统一。
        // 注：isInternalAdmin() 已被 !isExternal() 覆盖（前者 = is_admin==1 && !isExternal()），
        // 不再单独判断，避免两处条件日后走叉。
        if (!user.isExternal()) return true;

        // 外部用户（客户公司员工 / 客户管理员）：只能下载本公司 + 通用的文档。
        // A 客户的付费方案，B 客户绝不能下载 —— 与检索侧 buildQdrantFilter 同一套规则
        if (doc.getClientId() != null && !doc.getClientId().equals(user.getCompanyId())) {
            // 文档属于其他客户公司 → 拒绝
            return false;
        }
        if (doc.getClientId() != null && doc.getClientId().equals(user.getCompanyId())) {
            // 本公司专属文档，员工可直接下载
            return true;
        }
        // 通用文档：仅公开可下载
        return doc.getIsPublic() == 1;
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
        } else if (lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls")) {
            return excelDocumentServiceI.extractText(file);
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
                                .setSize(vectorSize)   // 与 embedding 模型维度一致，见 application.yaml
                                .setDistance(io.qdrant.client.grpc.Collections.Distance.Cosine)
                                .build();
                qdrantClient.createCollectionAsync(collectionName, vectorParams).get();
                log.info("✅ 集合 {} 创建成功，维度: {}", collectionName, vectorSize);
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
            // 存入部门ID。**已废弃，只是为了和存量向量的 payload 形状保持一致**：
            // 它恒为 LEGACY_DEPARTMENT_ID，检索端不再按它过滤
            // （见 RagServiceImpl.internalUserFilter 与 doc/商业化方案.md「A2」）。
            if (doc.getDepartmentId() != null) {
                metadata.put("department_id", doc.getDepartmentId().toString());
            }
            // 存入是否公开
            metadata.put("is_public", doc.getIsPublic() != null ? doc.getIsPublic().toString() : "0");
            // 客户公司隔离。null（通用方法论）用 "0" 标记 —— Qdrant 对缺失 key
            // 做 match 命中不到，必须显式存一个值才能在过滤时取到。
            //
            // 【改检索端时注意】这里只覆盖**新上传**的文档。迁移前入库的存量向量
            // （如《创新辞典》）payload 里**根本没有 client_id 这个键**，
            // 所以 RagServiceImpl.clientIsolatedFilter 的通用档必须同时判
            // `is_empty(client_id)` 和 `client_id == "0"` 两种形态，
            // 只写一种会漏掉另一半（这类漏是静默的：代码看着对、用户就是搜不到）。
            metadata.put("client_id", doc.getClientId() != null ? doc.getClientId().toString() : "0");
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

    @Override
    public StatisticsDTO getStatistics() {
        StatisticsDTO dto = new StatisticsDTO();

        // 文档总数
        dto.setTotalDocuments(documentMapper.selectCount(null));

        // 用户总数
        dto.setTotalUsers(userMapper.selectCount(null));

        // 近7天上传统计（按日期分组）
        List<Map<String, Object>> dailyList = documentMapper.selectDailyUploads(7);
        List<DailyUpload> dailyUploads = dailyList.stream()
                .map(row -> new DailyUpload(
                        row.get("date").toString(),
                        ((Number) row.get("count")).longValue()
                ))
                .collect(Collectors.toList());
        dto.setDailyUploads(dailyUploads);

        // 文件类型分布
        List<Map<String, Object>> typeList = documentMapper.selectFileTypeDistribution();
        Map<String, Long> typeMap = typeList.stream()
                .collect(Collectors.toMap(
                        row -> row.get("file_type").toString(),
                        row -> ((Number) row.get("count")).longValue()
                ));
        dto.setFileTypeDistribution(typeMap);

        // 公开/内部
        dto.setPublicDocuments(documentMapper.selectCount(new QueryWrapper<KbDocument>().eq("is_public", 1)));
        dto.setInternalDocuments(documentMapper.selectCount(new QueryWrapper<KbDocument>().eq("is_public", 0)));

        // 处理状态
        dto.setProcessedDocuments(documentMapper.selectCount(new QueryWrapper<KbDocument>().eq("status", 1)));
        dto.setPendingDocuments(documentMapper.selectCount(new QueryWrapper<KbDocument>().eq("status", 0)));

        return dto;
    }
}