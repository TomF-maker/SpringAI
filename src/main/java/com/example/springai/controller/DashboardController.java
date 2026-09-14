package com.example.springai.controller;

import com.example.springai.dto.QuestionStatisticsDTO;
import com.example.springai.dto.StatisticsDTO;
import com.example.springai.service.DocumentServiceI;
import com.example.springai.service.QuestionStatsServiceI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    @Autowired
    private DocumentServiceI documentService;

    @Autowired
    private QuestionStatsServiceI questionStatsService;

    @GetMapping("/statistics")
    @PreAuthorize("hasRole('ADMIN')")
    public StatisticsDTO getStatistics() {
        return documentService.getStatistics();
    }

    /**
     * 提问数据统计。
     *
     * <p>刻意做成独立接口而不是往 {@link StatisticsDTO} 里加字段，
     * 这样现有卡片与图表完全不受影响。
     *
     * @param days 统计窗口天数，默认 7
     * @param topN 热门问题条数，默认 10
     */
    @GetMapping("/question-statistics")
    @PreAuthorize("hasRole('ADMIN')")
    public QuestionStatisticsDTO getQuestionStatistics(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "10") int topN) {
        // 夹紧取值范围，避免被拼成奇怪的查询
        int safeDays = Math.min(Math.max(days, 1), 365);
        int safeTopN = Math.min(Math.max(topN, 1), 50);
        return questionStatsService.getQuestionStatistics(safeDays, safeTopN);
    }
}