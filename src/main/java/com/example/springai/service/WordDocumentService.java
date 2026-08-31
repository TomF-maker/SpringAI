package com.example.springai.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

@Slf4j
@Service
public class WordDocumentService implements WordDocumentServiceI {

    /**
     * 提取 Word 文档中的文本内容
     * 根据文件扩展名自动选择解析方式
     *
     * @param file 上传的 Word 文件
     * @return 提取的纯文本内容
     * @throws IOException 文件读取异常
     */
    @Override
    public String extractText(MultipartFile file) throws IOException {
        String fileName = file.getOriginalFilename();
        if (fileName == null) {
            throw new IOException("文件名无效");
        }

        try (InputStream inputStream = file.getInputStream()) {
            if (fileName.toLowerCase().endsWith(".docx")) {
                return extractDocx(inputStream);
            } else if (fileName.toLowerCase().endsWith(".doc")) {
                return extractDoc(inputStream);
            } else {
                throw new IOException("不支持的文件格式: " + fileName);
            }
        }
    }

    /**
     * 解析 .docx 文件（基于 XML 的格式）
     */
    private String extractDocx(InputStream inputStream) throws IOException {
        try (XWPFDocument document = new XWPFDocument(inputStream)) {
            XWPFWordExtractor extractor = new XWPFWordExtractor(document);
            String text = extractor.getText();
            log.info("📄 .docx 解析完成，共 {} 字符", text != null ? text.length() : 0);
            return text != null ? text : "";
        }
    }

    /**
     * 解析 .doc 文件（旧的二进制格式）
     */
    private String extractDoc(InputStream inputStream) throws IOException {
        try (HWPFDocument document = new HWPFDocument(inputStream)) {
            WordExtractor extractor = new WordExtractor(document);
            String text = extractor.getText();
            log.info("📄 .doc 解析完成，共 {} 字符", text != null ? text.length() : 0);
            return text != null ? text : "";
        }
    }
}