package com.example.springai.service.impl;

import com.example.springai.common.RegionNames;
import com.example.springai.dto.GeoStatisticsDTO;
import com.example.springai.dto.NameCount;
import com.example.springai.mapper.KbLoginLogMapper;
import com.example.springai.mapper.KbQuestionLogMapper;
import com.example.springai.service.GeoStatsServiceI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class GeoStatsService implements GeoStatsServiceI {

    @Autowired
    private KbQuestionLogMapper questionLogMapper;

    @Autowired
    private KbLoginLogMapper loginLogMapper;

    @Override
    public GeoStatisticsDTO getQuestionGeo(int days, int topN) {
        try {
            return assemble(days,
                    questionLogMapper.selectProvinceDistribution(days),
                    questionLogMapper.selectCityDistribution(days, topN),
                    questionLogMapper.selectGeoCoverage(days));
        } catch (DataAccessException e) {
            log.warn("提问地域统计不可用（kb_question_log 的归属地列是否已加？）: {}", e.getMessage());
            return unavailable(days);
        }
    }

    @Override
    public GeoStatisticsDTO getLoginGeo(int days, int topN) {
        try {
            return assemble(days,
                    loginLogMapper.selectProvinceDistribution(days),
                    loginLogMapper.selectCityDistribution(days, topN),
                    loginLogMapper.selectGeoCoverage(days));
        } catch (DataAccessException e) {
            log.warn("登录地域统计不可用（kb_login_log 是否已建表？）: {}", e.getMessage());
            return unavailable(days);
        }
    }

    private GeoStatisticsDTO assemble(int days,
                                      List<Map<String, Object>> provinceRows,
                                      List<Map<String, Object>> cityRows,
                                      Map<String, Object> coverage) {
        GeoStatisticsDTO dto = new GeoStatisticsDTO();
        dto.setDays(days);
        dto.setTotal(asLong(coverage, "total"));
        dto.setUnresolved(asLong(coverage, "unresolved"));
        dto.setResolved(dto.getTotal() - dto.getUnresolved());

        // 省：先归一化成地图认得的简称，再合并同类项。
        // 同一个省可能以「广东省」和「广东」两种写法都出现过（中途换过服务商、
        // 或手工插过测试数据）。不合并的话它们会成为地图上的两条数据，
        // ECharts 只认最后一个，前一个的数值静默消失。
        Map<String, Long> merged = new LinkedHashMap<>();
        long unmapped = 0;
        for (Map<String, Object> row : orEmpty(provinceRows)) {
            String raw = text(row.get("name"));
            if (raw == null) {
                continue;
            }
            long count = asLong(row, "count");
            String mapName = RegionNames.toMapName(raw);
            if (mapName == null) {
                continue;
            }
            if (!RegionNames.isKnownProvince(raw)) {
                // 有省名，但归一化后不在中国地图的 34 个名字里 —— 这是**代码问题**
                // （归一化漏了一种写法），不是数据问题。单独计数让它在页面上可见，
                // 同时仍然保留在 provinces 里，好让人看到到底是哪个名字。
                unmapped += count;
                log.warn("省份「{}」归一化成「{}」后不在中国地图的 34 个名字里，该省无法着色。"
                        + "多半是 RegionNames 的后缀表漏了这种写法", raw, mapName);
            }
            merged.merge(mapName, count, Long::sum);
        }
        dto.setUnmapped(unmapped);
        dto.setProvinces(toSorted(merged));

        // 市：不做归一化 —— 没有市级地图，城市排行按原始市名展示即可
        // （高德给的是「深圳市」这种全称，展示出来是对的）
        Map<String, Long> cities = new LinkedHashMap<>();
        for (Map<String, Object> row : orEmpty(cityRows)) {
            String raw = text(row.get("name"));
            if (raw == null) {
                continue;
            }
            cities.merge(raw, asLong(row, "count"), Long::sum);
        }
        dto.setCities(toSorted(cities));

        return dto;
    }

    private static GeoStatisticsDTO unavailable(int days) {
        GeoStatisticsDTO dto = new GeoStatisticsDTO();
        dto.setDays(days);
        dto.setUnavailable(true);
        return dto;
    }

    private static List<NameCount> toSorted(Map<String, Long> merged) {
        return merged.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> new NameCount(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
    }

    private static List<Map<String, Object>> orEmpty(List<Map<String, Object>> rows) {
        return rows == null ? Collections.emptyList() : rows;
    }

    private static long asLong(Map<String, Object> row, String key) {
        Object v = row == null ? null : row.get(key);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static String text(Object v) {
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
