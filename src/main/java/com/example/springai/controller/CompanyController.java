package com.example.springai.controller;

import com.example.springai.common.ErrorCode;
import com.example.springai.common.PageResult;
import com.example.springai.common.Response;
import com.example.springai.dto.CompanyDetailDTO;
import com.example.springai.dto.CompanyListDTO;
import com.example.springai.dto.CompanyMemberAdminRequest;
import com.example.springai.dto.CompanySaveRequest;
import com.example.springai.dto.UpdateStatusRequest;
import com.example.springai.exception.BizException;
import com.example.springai.service.CompanyAdminServiceI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 公司管理接口（开户 / 合约 / 停用 / 指定管理员）。
 *
 * <p><b>类级 {@code hasRole('ADMIN')} —— 客户永远进不来，这是刻意的。</b>
 * 这里的每一条都是伯伯公司的商业资产：在给哪几家做咨询、每家的年费与到期日、
 * 谁快到期了。客户管理员有自己的看板（{@code /api/client-admin/stats}），
 * 那边只能看到自己一家。
 *
 * <p>用类级而不是逐个方法标：这是个纯管理控制器，没有"对普通用户开放"的接口，
 * 逐个标只会在新增方法时漏掉一个（本仓库的约定见 CLAUDE.md「加一个接口」）。
 */
@Slf4j
@RestController
@RequestMapping("/api/companies")
@PreAuthorize("hasRole('ADMIN')")
public class CompanyController {

    @Autowired
    private CompanyAdminServiceI companyAdminService;

    /** 公司列表（带员工数 / 文档数 / 近 30 天提问数）。 */
    @GetMapping
    public Response<PageResult<CompanyListDTO>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword) {
        return Response.success(companyAdminService.listCompanies(page, size, keyword));
    }

    /** 公司详情：合约与状态、员工名单、最近文档、用量。 */
    @GetMapping("/{id:\\d+}")
    public Response<CompanyDetailDTO> detail(
            @PathVariable Long id,
            @RequestParam(defaultValue = "30") int days) {
        return Response.success(companyAdminService.getDetail(id, days));
    }

    /** 新建公司。返回 id，便于前端建完直接跳详情。 */
    @PostMapping
    public Response<Map<String, Object>> create(@RequestBody CompanySaveRequest request) {
        Long id = companyAdminService.create(request);
        Map<String, Object> data = new HashMap<>();
        data.put("id", id);
        return Response.success(data);
    }

    /** 编辑公司（名称 / 信用代码 / 到期日 / 席位 / 备注）。 */
    @PutMapping("/{id:\\d+}")
    public Response<Void> update(@PathVariable Long id, @RequestBody CompanySaveRequest request) {
        companyAdminService.update(id, request);
        return Response.success();
    }

    /** 停用 / 恢复。停用只让该公司的人进不来，资料一律保留。 */
    @PutMapping("/{id:\\d+}/status")
    public Response<Void> updateStatus(@PathVariable Long id,
                                       @RequestBody UpdateStatusRequest request) {
        companyAdminService.updateStatus(id, request.getStatus());
        return Response.success();
    }

    /**
     * 指定 / 取消该公司的客户管理员。
     *
     * <p>路径里同时带 companyId：{@code userId} 单独就能定位到人，但带上公司是为了
     * 让"把别家的人设成这家管理员"这种错在服务层能直接判掉（见 setMemberAdmin）。
     */
    @PutMapping("/{id:\\d+}/members/{userId:\\d+}/admin")
    public Response<Void> setMemberAdmin(@PathVariable Long id,
                                         @PathVariable Long userId,
                                         @RequestBody CompanyMemberAdminRequest request) {
        if (request.getIsAdmin() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少 isAdmin");
        }
        companyAdminService.setMemberAdmin(id, userId, request.getIsAdmin());
        return Response.success();
    }
}
