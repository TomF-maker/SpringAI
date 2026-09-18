package com.example.springai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.springai.common.CreditCode;
import com.example.springai.common.PageResult;
import com.example.springai.dto.ClientAdminStatsDTO;
import com.example.springai.dto.CompanyDetailDTO;
import com.example.springai.dto.CompanyListDTO;
import com.example.springai.dto.CompanySaveRequest;
import com.example.springai.entity.KbDocument;
import com.example.springai.entity.SysCompany;
import com.example.springai.entity.SysUser;
import com.example.springai.exception.BizException;
import com.example.springai.mapper.KbDocumentMapper;
import com.example.springai.mapper.SysCompanyMapper;
import com.example.springai.mapper.SysUserMapper;
import com.example.springai.service.ClientAdminStatsServiceI;
import com.example.springai.service.CompanyAdminServiceI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 公司管理实现。
 *
 * <p>三件容易做错的事，都在这里收口：
 * <ol>
 *   <li><b>停用不是禁用员工</b>：只翻 {@code sys_company.status}。批量改员工
 *       {@code status} 的话，续费后要再批量改回来，中间漏一个客户就永远登不进来。</li>
 *   <li><b>信用代码在编辑时也要查重</b>（排除自己）：客户填错代码后我们改过来，
 *       可能正好撞上另一家已登记的公司 —— 撞了就是"两家客户的资料混在一起"，
 *       在咨询行业这是事故。</li>
 *   <li><b>设客户管理员前必须确认对方是外部账号</b>：{@code is_admin=1} 配
 *       {@code user_type=1} 是内部 ADMIN（能看所有客户），配 2 才是客户管理员。
 *       少了这个校验，把内部账号设进来就等于提权。</li>
 * </ol>
 */
@Slf4j
@Service
public class CompanyAdminService implements CompanyAdminServiceI {

    /** 列表里"近 30 天提问数"的窗口。列表只回答"这家最近在不在用"，30 天足够。 */
    private static final int LIST_QUESTION_DAYS = 30;

    private static final int MAX_NAME_LENGTH = 128;
    private static final int MAX_NOTE_LENGTH = 255;

    /** 详情里的员工名单上限（防御性，见 SysUserMapper 的说明）。 */
    private static final int MEMBER_LIMIT = 200;
    /** 详情里的文档列表上限。 */
    private static final int DOCUMENT_LIMIT = 20;

    @Autowired
    private SysCompanyMapper companyMapper;

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private KbDocumentMapper documentMapper;

    /**
     * 客户看板的统计实现，直接复用。
     *
     * <p>不另写一套"管理员视角的用量查询"：客户自己看到的数字和我们看到的
     * 必须是同一套，否则客户来电说"我这个月明明问了 50 次"时两边对不上，
     * 没法解释。区别只在于 clientId 从哪来（那里来自登录用户，这里来自选中的公司）。
     */
    @Autowired
    private ClientAdminStatsServiceI clientAdminStatsService;

    @Override
    public PageResult<CompanyListDTO> listCompanies(int page, int size, String keyword) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        String kw = keyword == null || keyword.isBlank() ? null : keyword.trim();

        long total = companyMapper.countCompanies(kw);
        List<CompanyListDTO> records = companyMapper.selectCompanyOverview(
                kw, LIST_QUESTION_DAYS, (long) (safePage - 1) * safeSize, safeSize);

        LocalDateTime now = LocalDateTime.now();
        for (CompanyListDTO row : records) {
            fillBlockState(row, now);
        }
        return PageResult.of(records, total, safePage, safeSize);
    }

    @Override
    public CompanyDetailDTO getDetail(Long companyId, int days) {
        SysCompany company = requireCompany(companyId);
        LocalDateTime now = LocalDateTime.now();
        int safeDays = Math.min(Math.max(1, days), 365);

        CompanyDetailDTO dto = new CompanyDetailDTO();
        dto.setId(company.getId());
        dto.setCompanyName(company.getCompanyName());
        dto.setCreditCode(company.getCreditCode());
        dto.setStatus(company.getStatus());
        dto.setContractExpireAt(company.getContractExpireAt());
        dto.setSeatLimit(company.getSeatLimit());
        dto.setContractNote(company.getContractNote());
        dto.setCreatedAt(company.getCreatedAt());

        // 员工数给两个：总数让人知道"这家有多少账号"，启用数才是占席位的数量
        dto.setMemberCount(userMapper.selectCount(
                new QueryWrapper<SysUser>().eq("company_id", companyId)));
        dto.setActiveMemberCount(userMapper.countActiveByCompany(companyId));
        dto.setDocumentCount(documentMapper.selectCount(
                new QueryWrapper<KbDocument>().eq("client_id", companyId)));

        String reason = company.blockReason(now);
        dto.setBlocked(reason != null);
        dto.setBlockReason(reason);

        dto.setMembers(userMapper.selectMembersByCompany(companyId, MEMBER_LIMIT));
        dto.setDocuments(documentMapper.selectRecentByClient(companyId, DOCUMENT_LIMIT));

        // 用量：复用客户看板。它内部对"表未就绪"有降级（unavailable=true），
        // 所以这里不需要 try/catch —— 客户看板那边已经踩过这个坑并处理好了。
        ClientAdminStatsDTO stats = clientAdminStatsService.getStats(companyId, safeDays, 10);
        dto.setStats(stats);

        return dto;
    }

    @Override
    public Long create(CompanySaveRequest request) {
        String name = validateName(request.getCompanyName());
        String code = validateCreditCode(request.getCreditCode());
        SysCompany existing = findByCreditCode(code);
        if (existing != null) {
            throw new BizException("信用代码已存在：「" + existing.getCompanyName()
                    + "」。请直接在列表里编辑它，不要重复建 —— 两家客户共用一行会让资料混在一起");
        }

        SysCompany company = new SysCompany();
        company.setCompanyName(name);
        company.setCreditCode(code);
        company.setStatus(1);
        company.setContractExpireAt(parseExpireAt(request.getContractExpireAt()));
        company.setSeatLimit(validateSeatLimit(request.getSeatLimit()));
        company.setContractNote(validateNote(request.getContractNote()));
        try {
            companyMapper.insert(company);
        } catch (DuplicateKeyException e) {
            // 并发：两个管理员同时建同一家。唯一索引只放行一个，
            // 这里不能像注册那样"重查并复用" —— 建公司是明确的管理动作，
            // 复用会让后一个人以为建成功了，实际建的是别人那条。
            throw new BizException("该公司刚刚已被其他管理员创建，请刷新列表");
        }
        log.info("🏢 新建公司: id={}, 名称={}, 信用代码={}", company.getId(), name, code);
        return company.getId();
    }

    @Override
    public void update(Long companyId, CompanySaveRequest request) {
        SysCompany company = requireCompany(companyId);
        String name = validateName(request.getCompanyName());
        String code = validateCreditCode(request.getCreditCode());

        SysCompany sameCode = findByCreditCode(code);
        if (sameCode != null && !sameCode.getId().equals(companyId)) {
            throw new BizException("信用代码已被「" + sameCode.getCompanyName() + "」占用，请核对");
        }

        // 只写要改的列：用整个实体 updateById 会把读出来的旧快照写回去
        // （这条坑在本仓库踩过多次，见 CLAUDE.md）。
        // 另外「到期日 / 席位 / 备注」是**可以清空**的（不限、无备注），
        // 而 MyBatis-Plus 默认忽略实体里的 null —— 所以这三个走 UpdateWrapper
        // 显式 set，才能把已有值改回 NULL。实体那一路则负责让
        // MyMetaObjectHandler 正常刷新 updated_at。
        SysCompany update = new SysCompany();
        update.setCompanyName(name);
        update.setCreditCode(code);

        LocalDateTime expireAt = parseExpireAt(request.getContractExpireAt());
        Integer seatLimit = validateSeatLimit(request.getSeatLimit());
        String note = validateNote(request.getContractNote());

        companyMapper.update(update, new UpdateWrapper<SysCompany>()
                .eq("id", companyId)
                .set("contract_expire_at", expireAt)
                .set("seat_limit", seatLimit)
                .set("contract_note", note));

        log.info("🏢 更新公司: id={}, 名称={}, 到期={}, 席位={}", companyId, name, expireAt, seatLimit);
    }

    @Override
    public void updateStatus(Long companyId, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BizException("状态只能是 0（停用）或 1（正常）");
        }
        SysCompany company = requireCompany(companyId);

        SysCompany update = new SysCompany();
        update.setId(companyId);
        update.setStatus(status);
        companyMapper.updateById(update);

        long members = userMapper.countActiveByCompany(companyId);
        log.info("🏢 公司{}: id={}, 名称={}, 影响启用账号数={}",
                status == 1 ? "恢复" : "停用", companyId, company.getCompanyName(), members);
    }

    @Override
    public void setMemberAdmin(Long companyId, Long userId, boolean isAdmin) {
        requireCompany(companyId);
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException("员工不存在");
        }
        // 必须是本公司的员工：否则 A 公司的管理员可能被指定的其实是别家的人
        if (user.getCompanyId() == null || !user.getCompanyId().equals(companyId)) {
            throw new BizException("该员工不属于这家公司");
        }
        // 必须是外部账号：is_admin=1 + user_type=1 是内部 ADMIN（看所有客户）。
        // 这里只翻 is_admin，所以放过内部账号就等于一次提权。
        if (!user.isExternal()) {
            throw new BizException("内部账号不能设为客户管理员（那会变成内部管理员）");
        }

        SysUser update = new SysUser();
        update.setId(userId);
        update.setIsAdmin(isAdmin ? 1 : 0);
        userMapper.updateById(update);

        log.info("🏢 {}客户管理员: companyId={}, userId={}, username={}",
                isAdmin ? "指定" : "取消", companyId, userId, user.getUsername());
    }

    // ==================== 内部 ====================

    private SysCompany requireCompany(Long companyId) {
        SysCompany company = companyId == null ? null : companyMapper.selectById(companyId);
        if (company == null) {
            throw new BizException("公司不存在");
        }
        return company;
    }

    private SysCompany findByCreditCode(String creditCode) {
        return companyMapper.selectOne(
                new QueryWrapper<SysCompany>().eq("credit_code", creditCode).last("LIMIT 1"));
    }

    /** 列表行的停用/到期状态：与员工登录时看到的是同一句原因（{@link SysCompany#blockReason}）。 */
    private void fillBlockState(CompanyListDTO row, LocalDateTime now) {
        SysCompany probe = new SysCompany();
        probe.setStatus(row.getStatus());
        probe.setContractExpireAt(row.getContractExpireAt());
        String reason = probe.blockReason(now);
        row.setBlocked(reason != null);
        row.setBlockReason(reason);
        // 即将到期：让管理员在列表上就能扫出来（30 天内），不必逐个点进详情
        row.setDaysLeft(probe.daysLeft(now));
        row.setExpiringSoon(!row.getBlocked() && probe.expiringSoon(now));
    }

    private static String validateName(String companyName) {
        String name = companyName == null ? null : companyName.trim();
        if (name == null || name.isEmpty()) {
            throw new BizException("请填写公司名称");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new BizException("公司名称过长（最多 " + MAX_NAME_LENGTH + " 字）");
        }
        return name;
    }

    private static String validateCreditCode(String creditCode) {
        String code = CreditCode.normalize(creditCode);
        if (!CreditCode.isValid(code)) {
            throw new BizException("统一社会信用代码不正确（应为 18 位且校验位有效）");
        }
        return code;
    }

    /**
     * 解析到期日：{@code 2026-12-31} → 当天 23:59:59。
     *
     * <p>为什么不落在 00:00:00：那样等于把到期日当天也算过期，客户会觉得少了一天。
     * 空串/null 表示不限（未签或长期合约）。
     */
    private static LocalDateTime parseExpireAt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim()).atTime(23, 59, 59);
        } catch (DateTimeParseException e) {
            throw new BizException("到期日格式不正确（应为 2026-12-31 这种）");
        }
    }

    /** 席位上限：null = 不限；给了就必须 ≥1（0 是"谁也进不来"，不像是本意）。 */
    private static Integer validateSeatLimit(Integer seatLimit) {
        if (seatLimit == null) {
            return null;
        }
        if (seatLimit < 1) {
            throw new BizException("席位上限至少为 1；不限制请留空");
        }
        return seatLimit;
    }

    private static String validateNote(String note) {
        if (note == null) {
            return null;
        }
        String trimmed = note.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_NOTE_LENGTH) {
            throw new BizException("备注过长（最多 " + MAX_NOTE_LENGTH + " 字）");
        }
        return trimmed;
    }
}
