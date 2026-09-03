package com.example.springai.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class StatisticsDTO {
    private long totalDocuments;
    private long totalUsers;
    private long totalDepartments;
    private List<DailyUpload> dailyUploads; // 近7天
    private Map<String, Long> fileTypeDistribution; // 文件类型分布
    private long publicDocuments;
    private long internalDocuments;
    private long processedDocuments;
    private long pendingDocuments;
}