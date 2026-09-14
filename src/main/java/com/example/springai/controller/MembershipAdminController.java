package com.example.springai.controller;

import com.example.springai.common.Response;
import com.example.springai.dto.MembershipGrantRequest;
import com.example.springai.entity.SysUser;
import com.example.springai.service.MembershipServiceI;
import com.example.springai.service.UserServiceI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 会员发放/调整（仅管理员）。
 *
 * <p>永久会员不对积分开放，只能从这里发放 —— 所以这个入口是必需的，不是可选的。
 *
 * <p><b>刻意不往 {@code UserController} 里加方法</b>：那个文件是逐方法
 * {@code @PreAuthorize}，ADMIN 块在 {@code searchEmployees} 之后结束，
 * 新方法加在文件末尾会<b>静默变成"仅登录可用"</b>。
 * 那个文件的历史（类级注解曾导致 {@code /api/users/me} 对普通用户不可用）说明这个坑踩过一次。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/membership")
@PreAuthorize("hasRole('ADMIN')")
public class MembershipAdminController {

    @Autowired
    private MembershipServiceI membershipService;

    @Autowired
    private UserServiceI userService;

    /**
     * 发放 / 调整 / 取消会员。
     *
     * @param userId 目标用户
     * @param body   {@code memberType} 传空表示取消会员
     */
    @PutMapping("/{userId}")
    public Response<Void> grant(@PathVariable Long userId,
                                Authentication authentication,
                                @RequestBody MembershipGrantRequest body) {
        SysUser operator = userService.findByUsernameOrEmail(authentication.getName());
        membershipService.grant(userId, body.getMemberType(), body.getExpireAt(),
                operator == null ? null : operator.getId(), body.getRemark());
        return Response.success();
    }

    /** 查看某个用户的会员状态（管理页用）。 */
    @GetMapping("/{userId}")
    public Response<com.example.springai.dto.MembershipStatusDTO> status(@PathVariable Long userId) {
        return Response.success(membershipService.getStatus(userId));
    }
}
