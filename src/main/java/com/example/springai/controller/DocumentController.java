package com.example.springai.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.springai.common.ErrorCode;
import com.example.springai.common.PageResult;
import com.example.springai.common.Response;
import com.example.springai.dto.DocumentListDTO;
import com.example.springai.dto.DocumentUploadDTO;
import com.example.springai.dto.UrlUploadRequest;
import com.example.springai.entity.KbDocument;
import com.example.springai.entity.SysUser;
import com.example.springai.service.DocumentServiceI;
import com.example.springai.service.UserServiceI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 文档管理（上传 / 列表 / 删除 / 下载）—— <b>仅管理员</b>。
 *
 * <p>类级 {@code @PreAuthorize} 而不是逐个方法标：漏标一个方法就是一个洞，
 * 而这里每个方法都该是管理员专属。上传和删除会立刻改动所有人的检索结果，
 * 下载则等于绕过文档的部门/公开可见性直接把原文拿走 —— 让普通用户进来风险太大。
 *
 * <p><b>菜单里的 admin-only 不是访问控制</b>，只是个视觉提示：不隐藏菜单时
 * 普通用户点进来会看到一片 403，而隐藏了菜单他们仍然可以直接敲 URL 调接口。
 * 真正的门在这里。普通用户该有的只有「智能问答 + 历史记录」。
 *
 * <p>目前四个页面里只有 {@code documents.html} 调这些接口，所以收紧不会误伤
 * —— 加新页面要用文档接口时，记得它是管理员专属的。
 */
@RestController
@RequestMapping("/api/documents")
@PreAuthorize("hasRole('ADMIN')")
@Slf4j
public class DocumentController {

    @Autowired
    private DocumentServiceI documentService;

    @Autowired
    private UserServiceI userService;

    // ==================== 原有上传接口（兼容） ====================
    @PostMapping("/upload")
    public Response<Map<String, Object>> uploadDocument(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return Response.fail(ErrorCode.BAD_REQUEST, "文件不能为空");
        }
        String fileName = file.getOriginalFilename();
        // 与 DocumentServiceImpl.isSupportedFileType 的白名单保持一致。
        // 此前这里只放行 pdf/doc/docx，比 service 层还窄，导致 txt/md/xlsx 走这个接口会被拒。
        if (fileName == null || !isSupportedExtension(fileName)) {
            return Response.fail(ErrorCode.BAD_REQUEST,
                    "仅支持 PDF、DOC、DOCX、XLS、XLSX、TXT、MD 格式文件");
        }
        try {
            int chunkCount = documentService.processDocument(file);
            Map<String, Object> data = new HashMap<>();
            data.put("fileName", fileName);
            data.put("chunkCount", chunkCount);
            return Response.success(data);
        } catch (IOException e) {
            log.error("文档处理失败", e);
            return Response.fail(ErrorCode.INTERNAL_ERROR, "文档处理失败: " + e.getMessage());
        }
    }

    // ==================== 带元数据上传（含权限） ====================
    @PostMapping("/upload/metadata")
    public Response<KbDocument> uploadDocumentWithMetadata(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String title,
            @RequestParam Long departmentId,
            @RequestParam(defaultValue = "1") Integer visibleType,
            @RequestParam(defaultValue = "false") Boolean isPublic,
            Authentication authentication) {

        SysUser user = currentUser(authentication);

        DocumentUploadDTO meta = new DocumentUploadDTO();
        meta.setTitle(title);
        meta.setDepartmentId(departmentId);
        meta.setVisibleType(visibleType);
        meta.setIsPublic(isPublic);

        try {
            return Response.success(documentService.uploadDocument(file, meta, user.getId()));
        } catch (Exception e) {
            log.error("上传失败", e);
            return Response.fail(ErrorCode.BAD_REQUEST, e.getMessage());
        }
    }

    // ==================== 从 URL 上传 ====================
    @PostMapping("/upload/url")
    public Response<KbDocument> uploadFromUrl(@RequestBody UrlUploadRequest request,
                                              Authentication authentication) {
        SysUser user = currentUser(authentication);

        DocumentUploadDTO meta = new DocumentUploadDTO();
        meta.setTitle(request.getTitle());
        meta.setDepartmentId(request.getDepartmentId());
        meta.setVisibleType(request.getVisibleType() != null ? request.getVisibleType() : 1);
        meta.setIsPublic(request.getIsPublic() != null ? request.getIsPublic() : false);

        try {
            return Response.success(
                    documentService.uploadFromUrl(request.getUrl(), meta, user.getId()));
        } catch (Exception e) {
            log.error("URL上传失败", e);
            return Response.fail(ErrorCode.BAD_REQUEST, e.getMessage());
        }
    }

    // ==================== 文档列表 ====================
    @GetMapping("/list")
    public Response<PageResult<DocumentListDTO>> listDocuments(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long departmentId,
            Authentication authentication) {

        SysUser user = currentUser(authentication);
        Page<DocumentListDTO> p =
                documentService.listDocuments(page, size, keyword, departmentId, user.getId());
        return Response.success(PageResult.of(p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize()));
    }

    // ==================== 删除文档 ====================
    @DeleteMapping("/{id}")
    public Response<Void> deleteDocument(@PathVariable Long id, Authentication authentication) {
        SysUser user = currentUser(authentication);
        documentService.deleteDocument(id, user.getId());
        return Response.success();
    }

    // ==================== 下载文档 ====================
    // 【注意】这个接口返回二进制流，绝对不能用 Response 包裹：
    // 客户端拿它当文件下载（octet-stream + Content-Disposition），
    // 包成 JSON 信封会让下载直接坏掉。
    @GetMapping("/download/{id}")
    public ResponseEntity<Resource> downloadDocument(@PathVariable Long id,
                                                     Authentication authentication) {
        SysUser user = currentUser(authentication);

        Resource resource = documentService.downloadDocument(id, user.getId());
        KbDocument doc = documentService.getDocumentById(id);

        String fileName = doc.getFileName();
        String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedFileName)
                .body(resource);
    }

    // ==================== 获取文档详情 ====================
    @GetMapping("/{id}")
    public Response<KbDocument> getDocument(@PathVariable Long id) {
        return Response.success(documentService.getDocumentById(id));
    }

    /** 与 service 层保持一致的白名单，避免两个地方各写一份、时间一长就对不上。 */
    private boolean isSupportedExtension(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx")
                || lower.endsWith(".xls") || lower.endsWith(".xlsx")
                || lower.endsWith(".txt") || lower.endsWith(".md");
    }

    private SysUser currentUser(Authentication authentication) {
        SysUser user = userService.findByUsernameOrEmail(authentication.getName());
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        return user;
    }
}
