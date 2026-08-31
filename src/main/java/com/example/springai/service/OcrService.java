package com.example.springai.service;


import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;


import java.awt.image.BufferedImage;

@Slf4j
@Service
public class OcrService implements OcrServiceI{

    @Value("${ocr.tessdata.path:/usr/share/tesseract-ocr/4.00/tessdata/}")
    private String tessdataPath;

    @Value("${ocr.language:chi_sim+eng}")
    private String language;

    private Tesseract tesseract;

    @PostConstruct
    public void init() {
        tesseract = new Tesseract();
        tesseract.setDatapath(tessdataPath);
        tesseract.setLanguage(language);
        // 使用 LSTM 神经网络引擎（3 表示 LSTM only）
        tesseract.setOcrEngineMode(3);
        log.info("🔧 Tesseract OCR 初始化完成，数据路径: {}, 语言: {}", tessdataPath, language);
    }

    /**
     * 对图片进行 OCR 文字识别
     *
     * @param image 待识别的图片
     * @return 识别出的文字内容，识别失败或为空时返回空字符串
     */
    public String recognizeText(BufferedImage image) {
        if (image == null) {
            log.warn("图片为空，无法进行 OCR 识别");
            return "";
        }
        try {
            String result = tesseract.doOCR(image);
            if (result == null) {
                return "";
            }
            return result.trim();
        } catch (TesseractException e) {
            log.error("OCR 识别失败: {}", e.getMessage(), e);
            return "";
        }
    }
}
