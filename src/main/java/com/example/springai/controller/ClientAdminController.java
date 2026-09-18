package com.example.springai.controller;

import com.example.springai.common.ErrorCode;
import com.example.springai.common.Response;
import com.example.springai.dto.ClientAdminStatsDTO;
import com.example.springai.entity.SysUser;
import com.example.springai.service.ClientAdminStatsServiceI;
import com.example.springai.service.UserServiceI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户公司看板接口。
 *
 * <p><b>越权防线有三层，缺一不可：</b>
 * <ol>
 *   <li>{@code @PreAuthorize("hasRole('CLIENT_ADMIN')")} —— 只有"外部用户 + is_admin=1"
 *       能进。内部管理员（ROLE_ADMIN）反而进不来，这是刻意的：这是客户自己的看板，
 *       内部要数据走 {@code /api/dashboard/**}。</li>
 *   <li>{@code clientId 来自登录用户}，绝不接受请求参数 —— 否则 A 公司管理员
 *       改个 URL 就能看 B 公司。</li>
 *   <li>SQL 层每条查询都带 {@code company_id = #{clientId}}（见 KbQuestionLogMapper）。</li>
 * </ol>
 */
@Slf4j
@RestController
@RequestMapping("/api/client-admin")
public class ClientAdminController {

    @Autowired
    private ClientAdminStatsServiceI clientAdminStatsService;

    @Autowired
    private UserServiceI userServiceI;

    /**
     * 本公司看板数据。
     *
     * @param days 统计窗口天数，默认 30（客户看的是"这个月用得怎么样"）
     * @param topN 热门问题/活跃榜条数，默认 10
     */
    @GetMapping("/stats")
    @PreAuthorize("hasRole('CLIENT_ADMIN')")
    public Response<ClientAdminStatsDTO> stats(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "10") int topN,
            Authentication authentication) {
        SysUser user = userServiceI.findByUsernameOrEmail(authentication.getName());
        // 到这里 role 已经保证是客户管理员，但 companyId 仍可能为空（脏数据）——
        // service 收到 null 会直接返回 unavailable，不会去查 `company_id IS NULL` 那批人
        if (user == null || user.getCompanyId() == null) {
            return Response.fail(ErrorCode.INTERNAL_ERROR, "当前账号未绑定客户公司，无法查看看板");
        }
        return Response.success(clientAdminStatsService.getStats(
                user.getCompanyId(), clamp(days, 1, 365), clamp(topN, 1, 50)));
    }

    /** 夹紧取值范围，避免被拼成奇怪的查询。与 DashboardController 同一写法。 */
    private static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }
}
