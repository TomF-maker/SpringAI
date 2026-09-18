package com.example.springai.service.impl;

import com.example.springai.dto.ClientAdminStatsDTO;
import com.example.springai.dto.DailyQuestion;
import com.example.springai.dto.HotQuestionDTO;
import com.example.springai.dto.NameCount;
import com.example.springai.entity.KbQuestionLog;
import com.example.springai.entity.SysCompany;
import com.example.springai.mapper.KbQuestionLogMapper;
import com.example.springai.mapper.SysCompanyMapper;
import com.example.springai.service.ClientAdminStatsServiceI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 客户公司看板实现。
 *
 * <p>与内部看板 {@link QuestionStatsService} 是两套独立实现，不抽公共父类：
 * 两者查询口径虽像，但过滤条件与指标集合都不同（客户侧每条 SQL 都多一个
 * "本公司员工"的子查询），强行抽象反而要把过滤条件当参数传来传去。
 */
@Slf4j
@Service
public class ClientAdminStatsService implements ClientAdminStatsServiceI {

    @Autowired
    private KbQuestionLogMapper mapper;

    @Autowired
    private SysCompanyMapper companyMapper;

    /** 建表/加列未执行时只告警一次，避免每次刷新看板都刷一屏同样的日志。 */
    private final AtomicBoolean unavailableWarned = new AtomicBoolean(false);

    @Override
    public ClientAdminStatsDTO getStats(Long clientId, int days, int topN) {
        ClientAdminStatsDTO dto = new ClientAdminStatsDTO();
        dto.setDays(days);

        // 没有公司归属就不该进这个方法（Controller 已拦），但这里是"纵深防御"：
        // 真放进来也不能去查 — 下面每条 SQL 都是 company_id = #{clientId}，
        // 传 null 会命中 `company_id IS NULL`，把"没填公司"的用户数据当成一家客户返回。
        if (clientId == null) {
            dto.setUnavailable(true);
            return dto;
        }

        try {
            dto.setCompanyName(findCompanyName(clientId));
            fillTotals(dto, clientId, days);
            fillQuality(dto, clientId, days);
            dto.setDailyQuestions(mapDaily(mapper.selectClientDailyQuestions(clientId, days)));
            dto.setHotQuestions(mapHot(mapper.selectClientHotQuestions(clientId, days, topN)));
            dto.setActiveUserRank(mapActiveUsers(mapper.selectClientActiveUsers(clientId, days, topN)));
        } catch (DataAccessException e) {
            // 与内部看板同一套降级：表还没建时返回"暂无数据"提示，而不是 500
            if (unavailableWarned.compareAndSet(false, true)) {
                log.warn("客户看板统计不可用（kb_question_log / sys_user.company_id 是否已建？）: {}",
                        e.getMessage());
            }
            dto.setUnavailable(true);
        }
        return dto;
    }

    private String findCompanyName(Long clientId) {
        SysCompany company = companyMapper.selectById(clientId);
        return company == null ? null : company.getCompanyName();
    }

    private void fillTotals(ClientAdminStatsDTO dto, Long clientId, int days) {
        Map<String, Object> totals = mapper.selectClientTotals(clientId, days);
        long total = asLong(totals, "total");
        long activeUsers = asLong(totals, "activeUsers");

        dto.setTotalQuestions(total);
        dto.setActiveUsers(activeUsers);
        dto.setTotalConversations(asLong(totals, "conversations"));
        dto.setTodayQuestions(mapper.selectClientTodayCount(clientId));
        dto.setAvgQuestionsPerUser(activeUsers == 0
                ? 0d
                : round1((double) total / activeUsers));
    }

    private void fillQuality(ClientAdminStatsDTO dto, Long clientId, int days) {
        Map<String, Long> byType = new HashMap<>();
        for (Map<String, Object> row : mapper.selectClientHitTypeDistribution(clientId, days)) {
            Object key = row.get("hit_type");
            if (key != null) {
                byType.put(key.toString(), asLong(row, "count"));
            }
        }
        long doc = byType.getOrDefault(KbQuestionLog.HIT_DOC, 0L);
        long miss = byType.getOrDefault(KbQuestionLog.HIT_MISS, 0L);

        dto.setDocAnswerCount(doc);
        dto.setKbGapCount(miss);

        // 口径与内部看板一致：分母只算需要检索的提问（DOC + MISS）。
        long retrievalAttempts = doc + miss;
        dto.setHitRate(retrievalAttempts == 0
                ? 0d
                : round1(doc * 100d / retrievalAttempts));
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

    private List<NameCount> mapActiveUsers(List<Map<String, Object>> rows) {
        List<NameCount> list = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Object name = row.get("userName");
            // SQL 里已经 COALESCE 过，这里再兜一层：NameCount.name 约定永不为 null
            list.add(new NameCount(
                    name == null || name.toString().isBlank() ? "未知用户" : name.toString(),
                    asLong(row, "count")));
        }
        return list;
    }

    // ==================== 小工具 ====================

    private static long asLong(Map<String, Object> row, String key) {
        Object v = row == null ? null : row.get(key);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static double round1(double value) {
        return Math.round(value * 10d) / 10d;
    }
}
