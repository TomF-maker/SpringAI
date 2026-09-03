package com.example.springai.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.springai.dto.*;
import com.example.springai.entity.SysUser;

public interface UserServiceI {

    Page<UserListDTO> listUsers(int page, int size, String keyword, Integer status, Long departmentId);

    UserDetailDTO getUserDetail(Long userId);

    void updateStatus(Long userId, Integer status);

    void assignRoles(Long userId, java.util.List<Long> roleIds);

    String resetPassword(Long userId);  // 重置为默认密码

    UserInfoDTO getCurrentUserInfo(Long userId);
    void updateUserInfo(Long userId, UpdateUserInfoRequest request);
    void changePassword(Long userId, ChangePasswordRequest request);

    SysUser findByUsernameOrEmail(String usernameOrEmail);

    Page<UserListDTO> searchEmployees(int page, int size, String keyword);
}