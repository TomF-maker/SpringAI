package com.example.springai.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Excel 文档文本抽取。
 */
public interface ExcelDocumentServiceI {

    /**
     * 提取 Excel 中的文本内容。
     *
     * @param file .xlsx 或 .xls 文件
     * @return 按行展开的纯文本，行与行之间用空行分隔
     * @throws IOException 文件读取或解析异常
     */
    String extractText(MultipartFile file) throws IOException;
}
