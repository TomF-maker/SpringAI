package com.example.springai.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.springai.common.PageResult;
import com.example.springai.common.Response;
import com.example.springai.dto.PendingSuggestionDTO;
import com.example.springai.dto.SuggestionReviewRequest;
import com.example.springai.entity.KbPointsLog;
import com.example.springai.entity.SysUser;
import com.example.springai.service.AnswerFeedbackServiceI;
import com.example.springai.service.PointsServiceI;
import com.example.springai.service.UserServiceI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 优化意见审核（仅管理员）。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/feedback")
@PreAuthorize("hasRole('ADMIN')")
public class FeedbackAdminController {

    @Autowired
    private AnswerFeedbackServiceI feedbackService;

    @Autowired
    private PointsServiceI pointsService;

    @Autowired
    private UserServiceI userService;

    /**
     * 审核列表。
     *
     * @param status  PENDING / ACCEPTED / REJECTED，不传查全部
     * @param keyword 按意见内容模糊匹配，不传不过滤
     */
    @GetMapping("/suggestions")
    public Response<PageResult<PendingSuggestionDTO>> listSuggestions(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<PendingSuggestionDTO> p = feedbackService.listSuggestions(status, keyword, page, size);
        return Response.success(PageResult.of(p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize()));
    }

    /** 待审核条数，用于菜单角标。 */
    @GetMapping("/pending-count")
    public Response<Map<String, Object>> pendingCount() {
        Map<String, Object> data = new HashMap<>();
        data.put("count", feedbackService.countPending());
        return Response.success(data);
    }

    /**
     * 审核一条意见。采纳时给提建议的人加积分。
     *
     * <p>服务层用 {@code status='PENDING'} 的 CAS 更新做幂等，重复提交不会重复发分。
     */
    @PutMapping("/suggestions/{id}/review")
    public Response<Map<String, Object>> review(@PathVariable Long id,
                                                Authentication authentication,
                                                @RequestBody SuggestionReviewRequest request) {
        SysUser reviewer = userService.findByUsernameOrEmail(authentication.getName());
        int awarded = feedbackService.review(id, reviewer.getId(), request);

        Map<String, Object> data = new HashMap<>();
        data.put("awardedPoints", awarded);
        return Response.success(data);
    }

    /** 积分流水，可按用户过滤。 */
    @GetMapping("/points/logs")
    public Response<PageResult<KbPointsLog>> listPointsLogs(
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<KbPointsLog> p = pointsService.listLogs(userId, page, size);
        return Response.success(PageResult.of(p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize()));
    }
}
