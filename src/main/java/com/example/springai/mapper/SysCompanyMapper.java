package com.example.springai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.springai.entity.SysCompany;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface SysCompanyMapper extends BaseMapper<SysCompany> {

    /**
     * 每个公司有多少人（只算启用中的账号），按人数倒序。
     *
     * <p>用 INNER JOIN：没有用户的公司不出现在榜上。公司表里理论上不该有这种空壳
     * （注册时 find-or-create，创建即挂人），出现的话多半是有人手工删过用户。
     *
     * <p>"未填写"（{@code company_id IS NULL}）的那部分**不在这个查询里**，
     * 由 {@link #countUsersWithoutCompany()} 单独取 —— 它是"没有公司"而不是"某个公司"，
     * 混进来会让各公司人数之和对不上总用户数。
     */
    @Select("""
            SELECT c.company_name AS name, COUNT(*) AS count
            FROM sys_user u
            JOIN sys_company c ON c.id = u.company_id
            WHERE u.status = 1
            GROUP BY c.id, c.company_name
            ORDER BY count DESC
            """)
    List<Map<String, Object>> selectCompanyUserCounts();

    /** 没有挂公司的启用账号数（本功能上线前注册的老用户）。 */
    @Select("SELECT COUNT(*) FROM sys_user WHERE status = 1 AND company_id IS NULL")
    long countUsersWithoutCompany();
}
