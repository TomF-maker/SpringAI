package com.example.springai.service.impl;

import com.example.springai.dto.DailyQuestion;
import com.example.springai.dto.HotQuestionDTO;
import com.example.springai.dto.QuestionStatisticsDTO;
import com.example.springai.entity.KbQuestionLog;
import com.example.springai.mapper.KbQuestionLogMapper;
import com.example.springai.service.QuestionStatsServiceI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class QuestionStatsService implements QuestionStatsServiceI {

    @Autowired
    private KbQuestionLogMapper mapper;

    private final AtomicBoolean unavailableWarned = new AtomicBoolean(false);

    @Override
    public QuestionStatisticsDTO getQuestionStatistics(int days, int topN) {
        QuestionStatisticsDTO dto = new QuestionStatisticsDTO();
        dto.setDays(days);

        try {
            fillTotals(dto, days);
            fillHitTypes(dto, days);
            fillLatency(dto, days);
            dto.setToolCallCount(mapper.selectToolCallCount(days));
            dto.setDailyQuestions(mapDaily(mapper.selectDailyQuestions(days)));
            dto.setHotQuestions(mapHot(mapper.selectHotQuestions(days, topN)));
        } catch (DataAccessException e) {
            // 建表语句还没执行时不要让整个看板报错，退化为"暂无数据"并给出提示
            if (unavailableWarned.compareAndSet(false, true)) {
                log.warn("提问统计不可用（请先执行 doc/schema.sql 里的 kb_question_log 建表语句）: {}",
                        e.getMessage());
            }
            dto.setUnavailable(true);
        }
        return dto;
    }

    private void fillTotals(QuestionStatisticsDTO dto, int days) {
        Map<String, Object> totals = mapper.selectTotals(days);
        long total = asLong(totals, "total");
        long activeUsers = asLong(totals, "activeUsers");

        dto.setTotalQuestions(total);
        dto.setActiveUsers(activeUsers);
        dto.setTotalConversations(asLong(totals, "conversations"));
        dto.setTodayQuestions(mapper.selectTodayCount());
        dto.setAvgQuestionsPerUser(activeUsers == 0
                ? 0d
                : round1((double) total / activeUsers));
    }

    private void fillHitTypes(QuestionStatisticsDTO dto, int days) {
        Map<String, Long> byType = new HashMap<>();
        for (Map<String, Object> row : mapper.selectHitTypeDistribution(days)) {
            Object key = row.get("hit_type");
            if (key != null) {
                byType.put(key.toString(), asLong(row, "count"));
            }
        }
        long local = byType.getOrDefault(KbQuestionLog.HIT_LOCAL, 0L);
        long doc = byType.getOrDefault(KbQuestionLog.HIT_DOC, 0L);
        long miss = byType.getOrDefault(KbQuestionLog.HIT_MISS, 0L);
        long tool = byType.getOrDefault(KbQuestionLog.HIT_TOOL, 0L);
        long error = byType.getOrDefault(KbQuestionLog.HIT_ERROR, 0L);

        dto.setLocalHitCount(local);
        dto.setDocAnswerCount(doc);
        dto.setKbGapCount(miss);
        dto.setToolCallCount(tool);
        dto.setErrorCount(error);

        // 命中率的分母只算"需要检索的提问"：本地库命中是预置答案、工具调用根本不检索，
        // 把它们算进去会让这个数字虚高且没有指导意义。
        long retrievalAttempts = doc + miss;
        dto.setHitRate(retrievalAttempts == 0
                ? 0d
                : round1(doc * 100d / retrievalAttempts));
    }

    private void fillLatency(QuestionStatisticsDTO dto, int days) {
        Map<String, Object> latency = mapper.selectLatencyStats(days);
        dto.setAvgLatencyMs(Math.round(asDouble(latency, "avgLatency")));
    }

    private List<DailyQuestion> mapDaily(List<Map<String, Object>> rows) {
        List<DailyQuestion> list = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Object date = row.get("date");
            list.add(new DailyQuestion(
                    date == null ? "" : date.toString(),
                    asLong(row, "count"),
                    asLong(row, "activeUsers")));
        }
        return list;
    }

    private List<HotQuestionDTO> mapHot(List<Map<String, Object>> rows) {
        List<HotQuestionDTO> list = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Object question = row.get("question");
            Object lastAskedAt = row.get("lastAskedAt");
            list.add(new HotQuestionDTO(
                    question == null ? "" : question.toString(),
                    asLong(row, "count"),
                    lastAskedAt == null ? "" : lastAskedAt.toString()));
        }
        return list;
    }

    // ==================== 小工具 ====================

    private static long asLong(Map<String, Object> row, String key) {
        Object v = row == null ? null : row.get(key);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static double asDouble(Map<String, Object> row, String key) {
        Object v = row == null ? null : row.get(key);
        return v instanceof Number n ? n.doubleValue() : 0d;
    }

    private static double round1(double value) {
        return Math.round(value * 10d) / 10d;
    }
}
