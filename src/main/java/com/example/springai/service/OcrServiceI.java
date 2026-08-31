package com.example.springai.service;

import java.awt.image.BufferedImage;

public interface OcrServiceI {
    /**
     * 对图片进行 OCR 文字识别
     *
     * @param image 待识别的图片
     * @return 识别出的文字内容，识别失败或为空时返回空字符串
     */
    String recognizeText(BufferedImage image);
}
