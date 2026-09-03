package com.example.springai.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/documents")
@Slf4j
public class DocumentController {

    @Autowired
    private DocumentServiceI documentService;

    @Autowired
    private UserServiceI userService;

    // ==================== 原有上传接口（兼容） ====================
    @PostMapping("/upload")
    public Map<String, Object> uploadDocument(@RequestParam("file") MultipartFile file) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (file.isEmpty()) {
                response.put("success", false);
                response.put("message", "文件不能为空");
                return response;
            }
            String fileName = file.getOriginalFilename();
            if (fileName == null ||
                    !(fileName.toLowerCase().endsWith(".pdf") ||
                            fileName.toLowerCase().endsWith(".doc") ||
                            fileName.toLowerCase().endsWith(".docx"))) {
                response.put("success", false);
                response.put("message", "仅支持 PDF、DOC、DOCX 格式文件");
                return response;
            }
            int chunkCount = documentService.processDocument(file);
            response.put("success", true);
            response.put("message", "文档处理成功");
            response.put("fileName", fileName);
            response.put("chunkCount", chunkCount);
        } catch (IOException e) {
            response.put("success", false);
            response.put("message", "文档处理失败: " + e.getMessage());
        }
        return response;
    }

    // ==================== 带元数据上传（含权限） ====================
    @PostMapping("/upload/metadata")
    public Map<String, Object> uploadDocumentWithMetadata(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String title,
            @RequestParam Long departmentId,
            @RequestParam(defaultValue = "1") Integer visibleType,
            @RequestParam(defaultValue = "false") Boolean isPublic,
            Authentication authentication) {

        String username = authentication.getName();
        SysUser user = userService.findByUsernameOrEmail(username);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        DocumentUploadDTO meta = new DocumentUploadDTO();
        meta.setTitle(title);
        meta.setDepartmentId(departmentId);
        meta.setVisibleType(visibleType);
        meta.setIsPublic(isPublic);

        try {
            KbDocument doc = documentService.uploadDocument(file, meta, user.getId());
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "上传成功");
            result.put("data", doc);
            return result;
        } catch (Exception e) {
            log.error("上传失败", e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", e.getMessage());
            return result;
        }
    }

    // ==================== 从 URL 上传 ====================
    @PostMapping("/upload/url")
    public Map<String, Object> uploadFromUrl(@RequestBody UrlUploadRequest request,
                                             Authentication authentication) {
        String username = authentication.getName();
        SysUser user = userService.findByUsernameOrEmail(username);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        DocumentUploadDTO meta = new DocumentUploadDTO();
        meta.setTitle(request.getTitle());
        meta.setDepartmentId(request.getDepartmentId());
        meta.setVisibleType(request.getVisibleType() != null ? request.getVisibleType() : 1);
        meta.setIsPublic(request.getIsPublic() != null ? request.getIsPublic() : false);

        try {
            KbDocument doc = documentService.uploadFromUrl(request.getUrl(), meta, user.getId());
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "URL上传成功");
            result.put("data", doc);
            return result;
        } catch (Exception e) {
            log.error("URL上传失败", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return error;
        }
    }

    // ==================== 文档列表 ====================
    @GetMapping("/list")
    public Map<String, Object> listDocuments(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long departmentId,
            Authentication authentication) {

        String username = authentication.getName();
        SysUser user = userService.findByUsernameOrEmail(username);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        Page<DocumentListDTO> pageResult = documentService.listDocuments(page, size, keyword, departmentId, user.getId());
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", pageResult.getRecords());
        result.put("total", pageResult.getTotal());
        result.put("page", pageResult.getCurrent());
        result.put("size", pageResult.getSize());
        return result;
    }

    // ==================== 删除文档 ====================
    @DeleteMapping("/{id}")
    public Map<String, Object> deleteDocument(@PathVariable Long id,
                                              Authentication authentication) {
        String username = authentication.getName();
        SysUser user = userService.findByUsernameOrEmail(username);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        documentService.deleteDocument(id, user.getId());
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "文档删除成功");
        return result;
    }

    // ==================== 下载文档 ====================
    @GetMapping("/download/{id}")
    public ResponseEntity<Resource> downloadDocument(@PathVariable Long id,
                                                     Authentication authentication) {
        String username = authentication.getName();
        SysUser user = userService.findByUsernameOrEmail(username);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

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
    public Map<String, Object> getDocument(@PathVariable Long id) {
        KbDocument doc = documentService.getDocumentById(id);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", doc);
        return result;
    }
}