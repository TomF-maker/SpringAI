package com.example.springai.service.impl;

import com.example.springai.dto.CompanyStatisticsDTO;
import com.example.springai.dto.NameCount;
import com.example.springai.mapper.SysCompanyMapper;
import com.example.springai.service.CompanyStatsServiceI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class CompanyStatsService implements CompanyStatsServiceI {

    /** 老用户（本功能上线前注册，company_id 为 NULL）在报表里的标签。 */
    private static final String UNSPECIFIED = "未填写";

    @Autowired
    private SysCompanyMapper companyMapper;

    @Override
    public CompanyStatisticsDTO getCompanyStatistics() {
        CompanyStatisticsDTO dto = new CompanyStatisticsDTO();
        try {
            List<NameCount> companies = new ArrayList<>();
            long total = 0;

            for (Map<String, Object> row : orEmpty(companyMapper.selectCompanyUserCounts())) {
                if (row == null) {
                    continue;
                }
                Object raw = row.get("name");
                String name = (raw == null || raw.toString().isBlank())
                        ? UNSPECIFIED : raw.toString();
                long count = asLong(row, "count");
                companies.add(new NameCount(name, count));
                total += count;
            }

            // "未填写"单独查、单独补：它代表"没有挂公司"，而不是"某家叫未填写的公司"。
            // 把它混进上面那条 INNER JOIN 里会让各公司人数之和对不上总用户数，
            // 报表上就会有人问"剩下的几十个人去哪了"。
            long unspecified = companyMapper.countUsersWithoutCompany();
            if (unspecified > 0) {
                companies.add(new NameCount(UNSPECIFIED, unspecified));
                total += unspecified;
            }

            // 补完再统一排序：否则"未填写"永远落在末尾，
            // 哪怕它其实是人数最多的一组
            companies.sort(Comparator.comparingLong(NameCount::getCount).reversed());

            dto.setCompanies(companies);
            dto.setTotalUsers(total);
            // 公司数不含"未填写"那一行 —— 它不是一个公司
            dto.setCompanyCount(companies.size() - (unspecified > 0 ? 1 : 0));
        } catch (DataAccessException e) {
            // DDL 还没执行（sys_company 不存在 / sys_user.company_id 不存在）时不要 500。
            // 降级成 unavailable，前端显示提示而不是一张空图 ——
            // 空图会被读成"真的没有公司"。
            log.warn("公司统计不可用（sys_company 表与 sys_user.company_id 是否已建？）: {}", e.getMessage());
            dto.setUnavailable(true);
        }
        return dto;
    }

    private static List<Map<String, Object>> orEmpty(List<Map<String, Object>> rows) {
        return rows == null ? Collections.emptyList() : rows;
    }

    private static long asLong(Map<String, Object> row, String key) {
        Object v = row == null ? null : row.get(key);
        return v instanceof Number n ? n.longValue() : 0L;
    }
}
