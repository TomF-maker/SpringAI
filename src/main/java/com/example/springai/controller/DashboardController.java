package com.example.springai.controller;

import com.example.springai.common.Response;
import com.example.springai.dto.CompanyStatisticsDTO;
import com.example.springai.dto.GeoStatisticsDTO;
import com.example.springai.dto.QuestionStatisticsDTO;
import com.example.springai.dto.StatisticsDTO;
import com.example.springai.service.CompanyStatsServiceI;
import com.example.springai.service.DocumentServiceI;
import com.example.springai.service.GeoStatsServiceI;
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

    @Autowired
    private CompanyStatsServiceI companyStatsService;

    @Autowired
    private GeoStatsServiceI geoStatsService;

    @GetMapping("/statistics")
    @PreAuthorize("hasRole('ADMIN')")
    public Response<StatisticsDTO> getStatistics() {
        return Response.success(documentService.getStatistics());
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
    public Response<QuestionStatisticsDTO> getQuestionStatistics(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "10") int topN) {
        // 夹紧取值范围，避免被拼成奇怪的查询
        int safeDays = clamp(days, 1, 365);
        int safeTopN = clamp(topN, 1, 50);
        return Response.success(questionStatsService.getQuestionStatistics(safeDays, safeTopN));
    }

    /**
     * 每个公司多少人。
     *
     * <p>同样做成独立接口而不是塞进 {@link StatisticsDTO}（理由同上）。
     * 统计维度是 {@code sys_company}（JOIN {@code sys_user.company_id}），不是部门 ——
     * 公司是独立实体，不是组织架构的一级。
     */
    @GetMapping("/company-statistics")
    @PreAuthorize("hasRole('ADMIN')")
    public Response<CompanyStatisticsDTO> getCompanyStatistics() {
        return Response.success(companyStatsService.getCompanyStatistics());
    }

    /**
     * 提问的地域分布，供大屏用。
     *
     * <p>返回里带 {@code unresolved}（没有省份信息）和 {@code unmapped}
     * （有省份但地图不认得），前端必须把它们显示出来 ——
     * 否则会看到"广东占 40%"却以为剩下 60% 是境外流量。
     */
    @GetMapping("/question-geo")
    @PreAuthorize("hasRole('ADMIN')")
    public Response<GeoStatisticsDTO> getQuestionGeo(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "10") int topN) {
        return Response.success(geoStatsService.getQuestionGeo(clamp(days, 1, 365), clamp(topN, 1, 50)));
    }

    /** 登录的地域分布，供大屏用。语义同上。 */
    @GetMapping("/login-geo")
    @PreAuthorize("hasRole('ADMIN')")
    public Response<GeoStatisticsDTO> getLoginGeo(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "10") int topN) {
        return Response.success(geoStatsService.getLoginGeo(clamp(days, 1, 365), clamp(topN, 1, 50)));
    }

    /** 夹紧取值范围，避免被拼成奇怪的查询。 */
    private static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }
}