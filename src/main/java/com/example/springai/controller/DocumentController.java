package com.example.springai.controller;


import com.example.springai.service.DocumentServiceI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    @Autowired
    private DocumentServiceI documentService;

    /**
     * 上传PDF文档，自动向量化存入Qdrant
     * <p>
     * 测试方式：Postman 或 curl
     * curl -X POST http://localhost:8080/api/documents/upload -F "file=@你的文件.pdf"
     */
    @PostMapping("/upload")
    public Map<String, Object> uploadDocument(@RequestParam("file") MultipartFile file) {
        Map<String, Object> response = new HashMap<>();

        try {
            // 检查文件是否为空
            if (file.isEmpty()) {
                response.put("success", false);
                response.put("message", "文件不能为空");
                return response;
            }

            // 检查文件类型
            String fileName = file.getOriginalFilename();
            if (fileName == null ||
                    !(fileName.toLowerCase().endsWith(".pdf") ||
                            fileName.toLowerCase().endsWith(".doc") ||
                            fileName.toLowerCase().endsWith(".docx"))) {
                response.put("success", false);
                response.put("message", "仅支持 PDF、DOC、DOCX 格式文件");
                return response;
            }
            // 处理文档
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
}
