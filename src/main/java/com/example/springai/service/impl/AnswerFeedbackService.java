package com.example.springai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.springai.common.AnswerRating;
import com.example.springai.common.ErrorCode;
import com.example.springai.common.PointsRewardTier;
import com.example.springai.dto.FeedbackRequest;
import com.example.springai.dto.PendingSuggestionDTO;
import com.example.springai.dto.SuggestionReviewRequest;
import com.example.springai.entity.KbAnswerFeedback;
import com.example.springai.entity.KbFeedbackSuggestion;
import com.example.springai.entity.KbPointsLog;
import com.example.springai.exception.BizException;
import com.example.springai.mapper.KbAnswerFeedbackMapper;
import com.example.springai.mapper.KbFeedbackSuggestionMapper;
import com.example.springai.service.AnswerFeedbackServiceI;
import com.example.springai.service.PointsServiceI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AnswerFeedbackService implements AnswerFeedbackServiceI {

    /**
     * 优化意见的字数上限。和前端 chat.html 里 Swal 的 maxlength 是同一个值，
     * 改一处必须改另一处 —— 前端的只是体验，这里的才是约束。
     *
     * <p>DB 列是 {@code VARCHAR(1000)}，装得下，所以收紧到 800 不需要 DDL。
     */
    private static final int MAX_SUGGESTION_LENGTH = 800;

    /** 优化意见的字数下限。同样要和前端 Swal 的 inputValidator 保持一致。 */
    private static final int MIN_SUGGESTION_LENGTH = 5;

    @Autowired
    private KbAnswerFeedbackMapper feedbackMapper;

    @Autowired
    private KbFeedbackSuggestionMapper suggestionMapper;

    @Autowired
    private PointsServiceI pointsService;

    @Override
    @Transactional
    public void submit(Long userId, FeedbackRequest request) {
        AnswerRating rating = AnswerRating.parse(request.getRating());
        if (rating == null) {
            // 不默认成某一档：默认成"很有帮助"会污染统计
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择一个有效的评价");
        }

        KbAnswerFeedback feedback = upsertFeedback(userId, request, rating);

        // 只有低分档才收优化意见
        if (rating.isNeedsSuggestion() && StringUtils.hasText(request.getSuggestion())) {
            String suggestion = request.getSuggestion().trim();
            // 长度上限在这里兜底。前端的 maxlength 只是体验，绕过它（改 JS、
            // 直接打接口）太容易了 —— 而这字段会进管理端的审核列表，
            // 一条几万字的"意见"能把审核页撑爆。
            // 传超长**直接拒绝**而不是截断：截断后用户以为提交完整了，
            // 而管理员看到的是被砍掉一半、可能语义都不通的建议。
            if (suggestion.length() > MAX_SUGGESTION_LENGTH) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "优化意见最多 " + MAX_SUGGESTION_LENGTH + " 字，请精简一下");
            }
            // 下限和前端 Swal 的 inputValidator 是同一个值。少了它，绕过前端
            // 就能往审核队列里灌"啊""嗯"这种一个字的噪音 —— 管理员得逐条点开才知道没内容。
            if (suggestion.length() < MIN_SUGGESTION_LENGTH) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "优化意见至少 " + MIN_SUGGESTION_LENGTH + " 个字，方便我们定位问题");
            }
            upsertSuggestion(feedback.getId(), userId, suggestion);
        }
        log.info("📝 收到答案评价: userId={}, questionLogId={}, rating={}, 带意见={}",
                userId, request.getQuestionLogId(), rating.name(),
                rating.isNeedsSuggestion() && StringUtils.hasText(request.getSuggestion()));
    }

    /** 同一次提问只留一条评价；重复提交视为改评价。 */
    private KbAnswerFeedback upsertFeedback(Long userId, FeedbackRequest request, AnswerRating rating) {
        KbAnswerFeedback existing = null;
        if (request.getQuestionLogId() != null) {
            existing = feedbackMapper.selectOne(new QueryWrapper<KbAnswerFeedback>()
                    .eq("user_id", userId)
                    .eq("question_log_id", request.getQuestionLogId()));
        }

        if (existing != null) {
            existing.setRating(rating.name());
            existing.setAnswer(truncate(request.getAnswer(), 20000));
            feedbackMapper.updateById(existing);
            return existing;
        }

        KbAnswerFeedback row = new KbAnswerFeedback();
        row.setUserId(userId);
        row.setQuestionLogId(request.getQuestionLogId());
        row.setConversationId(request.getConversationId());
        row.setQuestion(truncate(request.getQuestion(), 1000));
        row.setAnswer(truncate(request.getAnswer(), 20000));
        row.setRating(rating.name());
        feedbackMapper.insert(row);
        return row;
    }

    /** 一条评价最多一条建议；重复提交覆盖正文，但**已审核过的不再改动**（否则发出去的分数就乱套了）。 */
    private void upsertSuggestion(Long feedbackId, Long userId, String content) {
        KbFeedbackSuggestion existing = suggestionMapper.selectOne(
                new QueryWrapper<KbFeedbackSuggestion>().eq("feedback_id", feedbackId));
        if (existing != null) {
            if (!KbFeedbackSuggestion.STATUS_PENDING.equals(existing.getStatus())) {
                log.info("建议已审核过，忽略本次修改: suggestionId={}", existing.getId());
                return;
            }
            existing.setContent(truncate(content, 1000));
            suggestionMapper.updateById(existing);
            return;
        }
        KbFeedbackSuggestion row = new KbFeedbackSuggestion();
        row.setFeedbackId(feedbackId);
        row.setUserId(userId);
        row.setContent(truncate(content, 1000));
        row.setStatus(KbFeedbackSuggestion.STATUS_PENDING);
        suggestionMapper.insert(row);
    }

    @Override
    public Page<PendingSuggestionDTO> listSuggestions(String status, String keyword, int page, int size) {
        Page<PendingSuggestionDTO> pageParam = new Page<>(page, size);
        String normalized = StringUtils.hasText(status) ? status.trim().toUpperCase() : null;

        // 模糊查询意见内容。用户输入里的 LIKE 通配符必须先转义，否则搜 "50%"
        // 会变成匹配所有含 "50" 的内容 —— 结果悄悄变多，看起来像是"搜到了"。
        // 转义符用 '!'（SQL 侧配 ESCAPE '!'），见 KbFeedbackSuggestionMapper 的说明。
        String like = null;
        if (StringUtils.hasText(keyword)) {
            String escaped = keyword.trim()
                    .replace("!", "!!")
                    .replace("%", "!%")
                    .replace("_", "!_");
            like = "%" + escaped + "%";
        }

        // 注意：MyBatis-Plus 的自定义分页方法，如果返回类型是 List（而不是 IPage），
        // 它只把 LIMIT 应用到 SQL 上、把结果作为**返回值**给出来，
        // **不会**回填到传入的 page 对象里。所以这里必须用返回值，
        // 读 page.getRecords() 会永远是空列表（而 total 却是对的，很容易误判成"查到了但没数据"）。
        List<PendingSuggestionDTO> records = suggestionMapper.selectSuggestions(pageParam, normalized, like);
        if (records == null) {
            records = Collections.emptyList();
        }

        // 枚举中文名不在 SQL 里查，统一在 Java 侧补，避免数据库里存中文
        records.forEach(dto -> {
            AnswerRating rating = AnswerRating.parse(dto.getRating());
            dto.setRatingLabel(rating == null ? null : rating.getLabel());
        });

        Page<PendingSuggestionDTO> result =
                new Page<>(pageParam.getCurrent(), pageParam.getSize(), pageParam.getTotal());
        result.setRecords(records);
        return result;
    }

    @Override
    @Transactional
    public int review(Long suggestionId, Long reviewerId, SuggestionReviewRequest request) {
        boolean accept = "ACCEPT".equalsIgnoreCase(
                request.getAction() == null ? "" : request.getAction().trim());

        KbFeedbackSuggestion suggestion = suggestionMapper.selectById(suggestionId);
        if (suggestion == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "该优化意见不存在");
        }

        Integer awardPoints = null;
        if (accept) {
            awardPoints = resolveAwardPoints(request);
        }

        // 用带 status='PENDING' 条件的 CAS 更新，保证一条建议只能被处理一次。
        // 如果两个管理员同时点"采纳"，只有一个的 UPDATE 会命中 1 行，另一个 0 行 ——
        // 这样就不可能在并发下重复发积分。
        KbFeedbackSuggestion update = new KbFeedbackSuggestion();
        update.setStatus(accept
                ? KbFeedbackSuggestion.STATUS_ACCEPTED
                : KbFeedbackSuggestion.STATUS_REJECTED);
        update.setAwardedPoints(awardPoints);
        update.setReviewRemark(truncate(request.getRemark(), 500));
        update.setReviewerId(reviewerId);
        update.setReviewedAt(LocalDateTime.now());

        int affected = suggestionMapper.update(update, new UpdateWrapper<KbFeedbackSuggestion>()
                .eq("id", suggestionId)
                .eq("status", KbFeedbackSuggestion.STATUS_PENDING));
        if (affected == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该意见已被处理过，请刷新后查看");
        }

        if (!accept) {
            log.info("优化意见已驳回: id={}, reviewerId={}", suggestionId, reviewerId);
            return 0;
        }

        pointsService.award(suggestion.getUserId(), awardPoints,
                KbPointsLog.REASON_SUGGESTION_ACCEPTED, suggestionId, reviewerId,
                "优化意见被采纳");
        log.info("✅ 优化意见已采纳: id={}, 加分={}, 受益用户={}",
                suggestionId, awardPoints, suggestion.getUserId());
        return awardPoints;
    }

    /**
     * 自定义积分优先；否则用档位；两个都没给就报错，不猜默认值。
     *
     * <p>自定义积分的下限是 <b>2</b>（大于 1 的正整数），不是 1。
     * 1 分在"采纳一条建议"这个语境下没有意义，更像个手滑或占位值，
     * 而积分一旦发出就要写流水，事后解释"为什么只给了 1 分"很尴尬。
     *
     * <p>前端校验和这里必须一致（见 feedback-review.html 的 readCustomPoints）。
     * 但**这里的才是约束** —— 直接打接口就能绕过前端。
     */
    private int resolveAwardPoints(SuggestionReviewRequest request) {
        Integer custom = request.getCustomPoints();
        if (custom != null) {
            if (custom <= 1 || custom > 10000) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "自定义积分需为大于 1 的整数，且不超过 10000");
            }
            return custom;
        }
        PointsRewardTier tier = PointsRewardTier.parse(request.getTier());
        if (tier == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择奖励档位，或填写自定义积分");
        }
        return tier.getPoints();
    }

    @Override
    public long countPending() {
        return suggestionMapper.countPending();
    }

    @Override
    public List<Map<String, Object>> ratingOptions() {
        List<Map<String, Object>> options = new ArrayList<>();
        for (AnswerRating rating : AnswerRating.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("value", rating.name());
            item.put("label", rating.getLabel());
            item.put("score", rating.getScore());
            item.put("needsSuggestion", rating.isNeedsSuggestion());
            options.add(item);
        }
        return options;
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
