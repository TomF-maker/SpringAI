package com.example.springai.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.springai.dto.*;
import com.example.springai.entity.SysUser;
import com.example.springai.service.UserServiceI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")  // 类级别控制，所有方法需 ADMIN 角色
public class UserController {

    @Autowired
    private UserServiceI userService;

    @GetMapping
    public Map<String, Object> listUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Long departmentId) {
        Page<UserListDTO> pageResult = userService.listUsers(page, size, keyword, status, departmentId);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", pageResult.getRecords());
        result.put("total", pageResult.getTotal());
        result.put("page", pageResult.getCurrent());
        result.put("size", pageResult.getSize());
        return result;
    }

    @GetMapping("/{id}")
    public Map<String, Object> getUserDetail(@PathVariable Long id) {
        UserDetailDTO detail = userService.getUserDetail(id);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", detail);
        return result;
    }

    @PutMapping("/{id}/status")
    public Map<String, Object> updateStatus(@PathVariable Long id, @RequestBody UpdateStatusRequest request) {
        userService.updateStatus(id, request.getStatus());
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "状态更新成功");
        return result;
    }

    @PutMapping("/{id}/roles")
    public Map<String, Object> assignRoles(@PathVariable Long id, @RequestBody AssignRoleRequest request) {
        userService.assignRoles(id, request.getRoleIds());
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "角色分配成功");
        return result;
    }

    @PutMapping("/{id}/password/reset")
    public Map<String, Object> resetPassword(@PathVariable Long id) {
        String newPassword = userService.resetPassword(id);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "密码已重置");
        result.put("newPassword", newPassword);  // 返回明文密码
        return result;
    }

    @GetMapping("/me")
    public Map<String, Object> getCurrentUser(Authentication authentication) {
        String username = authentication.getName();
        SysUser user = userService.findByUsernameOrEmail(username); // 需要新增此方法
        UserInfoDTO info = userService.getCurrentUserInfo(user.getId());
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", info);
        return result;
    }

    @PutMapping("/me")
    public Map<String, Object> updateUserInfo(Authentication authentication,
                                              @RequestBody UpdateUserInfoRequest request) {
        String username = authentication.getName();
        SysUser user = userService.findByUsernameOrEmail(username);
        userService.updateUserInfo(user.getId(), request);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "信息更新成功");
        return result;
    }

    @PutMapping("/me/password")
    public Map<String, Object> changePassword(Authentication authentication,
                                              @RequestBody ChangePasswordRequest request) {
        String username = authentication.getName();
        SysUser user = userService.findByUsernameOrEmail(username);
        userService.changePassword(user.getId(), request);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "密码修改成功");
        return result;
    }
}