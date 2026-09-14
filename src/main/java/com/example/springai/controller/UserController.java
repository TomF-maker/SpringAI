package com.example.springai.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.springai.common.PageResult;
import com.example.springai.common.Response;
import com.example.springai.dto.*;
import com.example.springai.entity.SysUser;
import com.example.springai.service.LoginSecurityServiceI;
import com.example.springai.service.UserServiceI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户管理。
 *
 * <p><b>注意这里没有类级别的 {@code @PreAuthorize}</b>：以前类上有
 * {@code @PreAuthorize("hasRole('ADMIN')")}，而它连带把 {@code /api/users/me}
 * 和 {@code /me/password} 也限成了管理员 —— 但侧边栏的"个人中心"对所有用户可见，
 * 所以普通用户点个人中心必然报错。现在改成逐方法标注：
 * 管理类接口（列表/详情/改状态/分配角色/重置密码/解锁/查员工）要求 ADMIN，
 * {@code /me*} 只要求已登录（由 {@code SecurityConfig} 的
 * {@code anyRequest().authenticated()} 兜住）。
 */
@Slf4j
@RestController
@RequestMapping("/api/users")
public class UserController {

    @Autowired
    private UserServiceI userService;

    @Autowired
    private LoginSecurityServiceI loginSecurityService;

    // ==================== 以下要求 ADMIN ====================

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Response<PageResult<UserListDTO>> listUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Long departmentId) {
        Page<UserListDTO> p = userService.listUsers(page, size, keyword, status, departmentId);
        return Response.success(PageResult.of(p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize()));
    }

    @GetMapping("/{id:\\d+}")
    @PreAuthorize("hasRole('ADMIN')")
    public Response<UserDetailDTO> getUserDetail(@PathVariable Long id) {
        return Response.success(userService.getUserDetail(id));
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public Response<Void> updateStatus(@PathVariable Long id, @RequestBody UpdateStatusRequest request) {
        userService.updateStatus(id, request.getStatus());
        return Response.success();
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize("hasRole('ADMIN')")
    public Response<Void> assignRoles(@PathVariable Long id, @RequestBody AssignRoleRequest request) {
        userService.assignRoles(id, request.getRoleIds());
        return Response.success();
    }

    @PutMapping("/{id}/password/reset")
    @PreAuthorize("hasRole('ADMIN')")
    public Response<Map<String, Object>> resetPassword(@PathVariable Long id) {
        String newPassword = userService.resetPassword(id);
        Map<String, Object> data = new HashMap<>();
        data.put("newPassword", newPassword);   // 返回明文密码，供管理员转告用户
        return Response.success(data);
    }

    /**
     * 逃生通道：清除某用户的异地登录状态（网段白名单 + last_login_ip）。
     *
     * <p>用户换手机号、收不到短信、或在前端被卡住时，管理员用它解锁，
     * 之后该用户下一次登录会被当作首次登录直接放行。
     */
    @PutMapping("/{id}/unlock-login")
    @PreAuthorize("hasRole('ADMIN')")
    public Response<Void> unlockLogin(@PathVariable Long id) {
        loginSecurityService.resetUser(id);
        return Response.success();
    }

    @GetMapping("/employees")
    @PreAuthorize("hasRole('ADMIN')")
    public Response<PageResult<UserListDTO>> searchEmployees(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword) {
        Page<UserListDTO> p = userService.searchEmployees(page, size, keyword);
        return Response.success(PageResult.of(p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize()));
    }

    // ==================== 以下只要求已登录 ====================

    @GetMapping("/me")
    public Response<UserInfoDTO> getCurrentUser(Authentication authentication) {
        SysUser user = userService.findByUsernameOrEmail(authentication.getName());
        return Response.success(userService.getCurrentUserInfo(user.getId()));
    }

    @PutMapping("/me")
    public Response<Void> updateUserInfo(Authentication authentication,
                                         @RequestBody UpdateUserInfoRequest request) {
        SysUser user = userService.findByUsernameOrEmail(authentication.getName());
        userService.updateUserInfo(user.getId(), request);
        return Response.success();
    }

    @PutMapping("/me/password")
    public Response<Void> changePassword(Authentication authentication,
                                         @RequestBody ChangePasswordRequest request) {
        SysUser user = userService.findByUsernameOrEmail(authentication.getName());
        userService.changePassword(user.getId(), request);
        return Response.success();
    }
}
