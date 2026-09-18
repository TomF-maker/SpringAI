package com.example.springai.controller;

import com.example.springai.common.AppTime;
import com.example.springai.common.Response;
import com.example.springai.dto.ContractStatusDTO;
import com.example.springai.entity.SysCompany;
import com.example.springai.entity.SysUser;
import com.example.springai.service.CompanyServiceI;
import com.example.springai.service.UserServiceI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前用户自己公司的服务状态（站内到期提醒横幅的数据源）。
 *
 * <p><b>为什么不能挂在 {@code CompanyController} 上</b>：那个控制器是类级
 * {@code hasRole('ADMIN')}，而这里的调用方恰恰是**客户**（客户管理员 / 客户员工）——
 * 类级与方法级注解取交集、只能收窄不能放宽，所以只能单独一个控制器。
 * 这里的每个方法都只返回"调用者自己公司"的信息，companyId 一律来自登录态、
 * 绝不接受请求参数（同 {@code ClientAdminController} 的做法）。
 */
@Slf4j
@RestController
@RequestMapping("/api/company")
public class CompanySelfController {

    @Autowired
    private UserServiceI userService;

    @Autowired
    private CompanyServiceI companyService;

    @GetMapping("/contract-status")
    public Response<ContractStatusDTO> contractStatus(Authentication authentication) {
        ContractStatusDTO dto = new ContractStatusDTO();

        SysUser user = userService.findByUsernameOrEmail(authentication.getName());
        if (user == null || user.getCompanyId() == null) {
            // 内部账号（含 admin）不挂公司 —— 前端据此不渲染横幅
            dto.setApplicable(false);
            return Response.success(dto);
        }

        SysCompany company = companyService.findById(user.getCompanyId());
        if (company == null) {
            // 公司行没了（脏数据）：不报错、也不显示任何东西。
            // 这里刻意不抛异常 —— 横幅是锦上添花，不能因为查不到公司把页面搞崩。
            dto.setApplicable(false);
            return Response.success(dto);
        }

        dto.setApplicable(true);
        dto.setCompanyName(company.getCompanyName());

        Integer daysLeft = company.daysLeft(AppTime.now());
        dto.setDaysLeft(daysLeft);
        if (company.getContractExpireAt() != null) {
            dto.setExpireDate(company.getContractExpireAt().toLocalDate().toString());
        }
        dto.setExpiringSoon(company.expiringSoon(AppTime.now()));
        return Response.success(dto);
    }
}
