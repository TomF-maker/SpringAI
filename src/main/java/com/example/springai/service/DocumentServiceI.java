package com.example.springai.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.springai.dto.DocumentListDTO;
import com.example.springai.dto.DocumentUploadDTO;
import com.example.springai.entity.KbDocument;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 文档处理服务接口
 * 作用：定义文档上传、解析、向量化存储的核心业务契约。
 * 设计原因：
 * 1. 面向接口编程，降低 Controller 与具体实现的耦合
 * 2. 便于后续扩展多种文档格式（PDF、Word、TXT 等）
 * 3. 便于单元测试时使用 Mock 对象
 */
public interface DocumentServiceI {

    /**
     * 处理上传的文档，提取文本并向量化存入 Qdrant
     *
     * @param file 上传的文档文件（目前支持 PDF）
     * @return 切分后的文档片段数量
     * @throws IOException 文件读取或解析异常
     */
    int processDocument(MultipartFile file) throws IOException;

    KbDocument uploadDocument(MultipartFile file, DocumentUploadDTO metadata, Long currentUserId) throws IOException;
    KbDocument uploadFromUrl(String url, DocumentUploadDTO metadata, Long currentUserId) throws IOException;
    Page<DocumentListDTO> listDocuments(int page, int size, String keyword, Long departmentId, Long currentUserId);
    void deleteDocument(Long docId, Long currentUserId);
    org.springframework.core.io.Resource downloadDocument(Long docId, Long currentUserId);
    KbDocument getDocumentById(Long id);

    int processDocument(MultipartFile file, Long documentId) throws IOException;
}
