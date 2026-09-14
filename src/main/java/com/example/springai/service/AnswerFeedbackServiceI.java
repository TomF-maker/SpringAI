package com.example.springai.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.springai.dto.FeedbackRequest;
import com.example.springai.dto.PendingSuggestionDTO;
import com.example.springai.dto.SuggestionReviewRequest;

import java.util.List;
import java.util.Map;

/**
 * 答案评价 + 优化意见 + 审核发分。
 */
public interface AnswerFeedbackServiceI {

    /**
     * 提交评价。同一用户对同一次提问重复提交会覆盖上一次的评价。
     *
     * @param userId 评价人。匿名用户不能评价（调用方保证非 null）
     */
    void submit(Long userId, FeedbackRequest request);

    /**
     * 管理端：分页查询优化意见。
     *
     * @param status  PENDING / ACCEPTED / REJECTED，传 null 查全部
     * @param keyword 按意见内容模糊匹配，传 null 或空白不过滤
     */
    Page<PendingSuggestionDTO> listSuggestions(String status, String keyword, int page, int size);

    /**
     * 管理端：审核一条优化意见。采纳时给提建议的人加积分。
     *
     * @return 实际发放的积分（驳回时为 0）
     */
    int review(Long suggestionId, Long reviewerId, SuggestionReviewRequest request);

    /** 待审核条数。 */
    long countPending();

    /** 评价档位选项，给前端渲染按钮用：[{value, label, score, needsSuggestion}]。 */
    List<Map<String, Object>> ratingOptions();
}
