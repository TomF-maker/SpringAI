package com.example.springai.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.springai.dto.DocumentListDTO;
import com.example.springai.dto.DocumentUploadDTO;
import com.example.springai.dto.StatisticsDTO;
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

    /**
     * 用字节数组上传一份文档。
     *
     * <p><b>给异步批量任务用</b>：{@code MultipartFile} 绑定在 Servlet 请求上，
     * 请求线程一返回它就读不出来了（getInputStream 直接报 MultipartFile 已清理）。
     * 所以批量任务在提交线程把文件落到临时文件，后台线程再读成字节调这个方法。
     *
     * <p>校验（类型 / 大小 / 空文件）与入库流程和 {@link #uploadDocument} 完全一致 ——
     * 它就是包一层转调，不另起一套逻辑。
     *
     * @param content       文件内容
     * @param originalName  原始文件名（标题为空时用它当标题，也用它判扩展名）
     */
    KbDocument uploadDocumentBytes(byte[] content, String originalName, DocumentUploadDTO metadata,
                                   Long currentUserId) throws IOException;
    /**
     * 文档列表（带权限 + 客户隔离）。
     *
     * @param clientId 管理员可按客户筛选；非 null 时只返回该客户的专属 + 通用文档。
     *                 仅管理员有效，普通用户的 client 隔离由其 companyId 决定。
     *                 没有 departmentId 参数：部门维度已去掉，见 doc/商业化方案.md「A2」。
     */
    Page<DocumentListDTO> listDocuments(int page, int size, String keyword, Long clientId, Long currentUserId);
    void deleteDocument(Long docId, Long currentUserId);
    org.springframework.core.io.Resource downloadDocument(Long docId, Long currentUserId);
    KbDocument getDocumentById(Long id);

    /**
     * 带可见性校验的文档详情查询。
     *
     * <p>{@link #getDocumentById} 是无校验的裸查询，只该给内部管理员用。
     * 文档管理接口放开给客户管理员之后，详情接口必须走这个方法 ——
     * 否则客户管理员把 URL 里的 id 一改，就能读到别家客户的文档元数据
     * （标题、文件名、上传人、部门），虽然不是正文，但已经泄露了
     * "别家在做什么咨询"这件事。
     *
     * <p>无权访问时抛 404 语义的异常，**不区分"不存在"和"无权访问"** ——
     * 否则反复试 id 就能探出哪些文档是真实存在的（同会话归属的处理）。
     */
    KbDocument getDocumentForUser(Long docId, Long currentUserId);

    int processDocument(MultipartFile file, Long documentId) throws IOException;

    int processDocument(MultipartFile file, Long documentId, KbDocument doc) throws IOException;

    StatisticsDTO getStatistics();
}
