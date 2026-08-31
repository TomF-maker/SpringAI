package com.example.springai.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

public interface WordDocumentServiceI {

    /**
     * 提取 Word 文档中的文本内容
     * 根据文件扩展名自动选择解析方式
     *
     * @param file 上传的 Word 文件
     * @return 提取的纯文本内容
     * @throws IOException 文件读取异常
     */
    String extractText(MultipartFile file) throws IOException;
}
