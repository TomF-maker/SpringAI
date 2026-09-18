package com.example.springai.service;

import com.example.springai.common.PageResult;
import com.example.springai.dto.CompanyDetailDTO;
import com.example.springai.dto.CompanyListDTO;
import com.example.springai.dto.CompanySaveRequest;

/**
 * 公司管理（内部管理员用）。
 *
 * <p>这是"开户"这条商业动作的载体：新建公司 → 导入员工 → 签合约（到期日 / 席位）
 * → 停用或续费。它替换掉了原来的部门管理页（部门维度已废弃，见
 * {@code doc/商业化方案.md} 的 A2）。
 *
 * <p>本接口只被 {@code /api/companies} 调用，那个控制器是 {@code hasRole('ADMIN')}——
 * 客户公司的管理员**看不到**这里：那是伯伯公司的商业资产（在给哪几家做咨询、
 * 每家的年费与到期日）。
 */
public interface CompanyAdminServiceI {

    /**
     * 公司列表（带员工数 / 文档数 / 近 30 天提问数）。
     *
     * @param keyword 按公司名或信用代码模糊搜索，可空
     */
    PageResult<CompanyListDTO> listCompanies(int page, int size, String keyword);

    /**
     * 公司详情：合约与状态、员工名单、最近文档、用量。
     *
     * @param days 用量统计窗口天数
     */
    CompanyDetailDTO getDetail(Long companyId, int days);

    /** 新建公司，返回公司 id。信用代码重复时拒绝（让管理员去列表里编辑已有的那家）。 */
    Long create(CompanySaveRequest request);

    /** 编辑公司。信用代码重复（且不是自己）时拒绝。 */
    void update(Long companyId, CompanySaveRequest request);

    /** 停用 / 恢复。停用不删任何资料，只让该公司的人进不来。 */
    void updateStatus(Long companyId, Integer status);

    /**
     * 把某家公司的某个员工设为 / 取消"客户管理员"。
     *
     * <p>只动 {@code is_admin} 这一列，{@code user_type} 保持 2 ——
     * 两者共同决定角色，见 {@code UserDetailsServiceImpl.getRoleCode}。
     */
    void setMemberAdmin(Long companyId, Long userId, boolean isAdmin);
}
