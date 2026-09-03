package com.example.springai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.springai.dto.*;
import com.example.springai.entity.SysDepartment;
import com.example.springai.entity.SysRole;
import com.example.springai.entity.SysUser;
import com.example.springai.entity.SysUserRole;
import com.example.springai.mapper.SysDepartmentMapper;
import com.example.springai.mapper.SysRoleMapper;
import com.example.springai.mapper.SysUserMapper;
import com.example.springai.mapper.SysUserRoleMapper;
import com.example.springai.service.EmailServiceI;
import com.example.springai.service.UserServiceI;
import com.example.springai.utils.PasswordGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class UserServiceImpl implements UserServiceI {

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private SysDepartmentMapper departmentMapper;

    @Autowired
    private SysRoleMapper roleMapper;

    @Autowired
    private SysUserRoleMapper userRoleMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private EmailServiceI emailService;
    @Override
    public Page<UserListDTO> listUsers(int page, int size, String keyword, Integer status, Long departmentId) {
        Page<SysUser> pageParam = new Page<>(page, size);
        QueryWrapper<SysUser> wrapper = new QueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like("username", keyword).or().like("email", keyword).or().like("real_name", keyword));
        }
        if (status != null) {
            wrapper.eq("status", status);
        }
        if (departmentId != null) {
            wrapper.eq("department_id", departmentId);
        }
        wrapper.orderByDesc("created_at");

        Page<SysUser> userPage = userMapper.selectPage(pageParam, wrapper);

        // 转换为 DTO
        Page<UserListDTO> dtoPage = new Page<>(userPage.getCurrent(), userPage.getSize(), userPage.getTotal());
        List<UserListDTO> dtoList = userPage.getRecords().stream().map(user -> {
            UserListDTO dto = new UserListDTO();
            dto.setId(user.getId());
            dto.setUsername(user.getUsername());
            dto.setEmail(user.getEmail());
            dto.setRealName(user.getRealName());
            dto.setUserType(user.getUserType());
            dto.setStatus(user.getStatus());
            dto.setCreatedAt(user.getCreatedAt());
            // 查询部门名称
            if (user.getDepartmentId() != null) {
                SysDepartment dept = departmentMapper.selectById(user.getDepartmentId());
                if (dept != null) {
                    dto.setDepartmentName(dept.getDeptName());
                }
            }
            // 查询角色名称
            List<SysUserRole> userRoles = userRoleMapper.selectList(
                    new QueryWrapper<SysUserRole>().eq("user_id", user.getId())
            );
            if (!userRoles.isEmpty()) {
                List<Long> roleIds = userRoles.stream().map(SysUserRole::getRoleId).collect(Collectors.toList());
                List<SysRole> roles = roleMapper.selectBatchIds(roleIds);
                String roleNames = roles.stream().map(SysRole::getRoleName).collect(Collectors.joining(","));
                dto.setRoleNames(roleNames);
            }
            return dto;
        }).collect(Collectors.toList());
        dtoPage.setRecords(dtoList);
        return dtoPage;
    }

    @Override
    public UserDetailDTO getUserDetail(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        UserDetailDTO dto = new UserDetailDTO();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setEmail(user.getEmail());
        dto.setPhone(user.getPhone());
        dto.setRealName(user.getRealName());
        dto.setAvatar(user.getAvatar());
        dto.setDepartmentId(user.getDepartmentId());
        dto.setUserType(user.getUserType());
        dto.setStatus(user.getStatus());
        dto.setIsAdmin(user.getIsAdmin());
        dto.setLastLoginTime(user.getLastLoginTime());
        dto.setCreatedAt(user.getCreatedAt());
        dto.setUpdatedAt(user.getUpdatedAt());

        // 部门名称
        if (user.getDepartmentId() != null) {
            SysDepartment dept = departmentMapper.selectById(user.getDepartmentId());
            if (dept != null) {
                dto.setDepartmentName(dept.getDeptName());
            }
        }
        // 角色名称
        List<SysUserRole> userRoles = userRoleMapper.selectList(
                new QueryWrapper<SysUserRole>().eq("user_id", userId)
        );
        if (!userRoles.isEmpty()) {
            List<Long> roleIds = userRoles.stream().map(SysUserRole::getRoleId).collect(Collectors.toList());
            List<SysRole> roles = roleMapper.selectBatchIds(roleIds);
            String roleNames = roles.stream().map(SysRole::getRoleName).collect(Collectors.joining(","));
            dto.setRoleNames(roleNames);
        }
        List<Long> roleIds = userRoles.stream().map(SysUserRole::getRoleId).collect(Collectors.toList());
        dto.setRoleIds(roleIds);
        return dto;
    }

    @Override
    @Transactional
    public void updateStatus(Long userId, Integer status) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        // 禁止禁用自己
        if (userId == 1L && status == 0) {
            throw new RuntimeException("不能禁用管理员账号");
        }
        user.setStatus(status);
        userMapper.updateById(user);
        log.info("用户 {} 状态已更新为 {}", userId, status == 1 ? "启用" : "禁用");
    }

    @Override
    @Transactional
    public void assignRoles(Long userId, List<Long> roleIds) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        // 删除旧角色
        userRoleMapper.delete(new QueryWrapper<SysUserRole>().eq("user_id", userId));
        // 添加新角色
        if (roleIds != null && !roleIds.isEmpty()) {
            for (Long roleId : roleIds) {
                SysUserRole userRole = new SysUserRole();
                userRole.setUserId(userId);
                userRole.setRoleId(roleId);
                userRoleMapper.insert(userRole);
            }
        }
        log.info("用户 {} 的角色已更新", userId);
    }

    @Override
    @Transactional
    public String resetPassword(Long userId) {
        // 1. 获取当前登录用户ID
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw new RuntimeException("未登录");
        }
        Long currentUserId = getCurrentUserId(authentication); // 自定义方法，从认证中提取userId

        // 2. 不能重置自己的密码
        if (currentUserId.equals(userId)) {
            throw new RuntimeException("不能重置自己的密码");
        }

        // 3. 检查目标用户是否存在
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        // 4. 生成强密码
        String rawPassword = PasswordGenerator.generateStrongPassword(12);
        String encoded = passwordEncoder.encode(rawPassword);
        user.setPassword(encoded);
        userMapper.updateById(user);
        // 发送邮件
        try {
            emailService.sendPasswordResetEmail(user.getEmail(), user.getUsername(), rawPassword);
        } catch (Exception e) {
            log.error("发送邮件失败，但密码已重置: {}", e.getMessage());
            // 抛出异常或记录，但继续返回密码（管理员可手动告知）
            throw new RuntimeException("密码重置成功，但邮件发送失败，请手动告知用户。");
        }
        log.info("用户 {} 密码已重置", userId);
        return rawPassword; // 返回明文密码（供管理员告知用户）
    }
    @Override
    public UserInfoDTO getCurrentUserInfo(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        UserInfoDTO dto = new UserInfoDTO();
        BeanUtils.copyProperties(user, dto);
        // 部门名称
        if (user.getDepartmentId() != null) {
            SysDepartment dept = departmentMapper.selectById(user.getDepartmentId());
            if (dept != null) {
                dto.setDepartmentName(dept.getDeptName());
            }
        }
        return dto;
    }

    @Override
    @Transactional
    public void updateUserInfo(Long userId, UpdateUserInfoRequest request) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        // 只允许修改部分字段
        if (request.getRealName() != null) {
            user.setRealName(request.getRealName());
        }
        if (request.getPhone() != null) {
            user.setPhone(request.getPhone());
        }
        if (request.getEmail() != null) {
            // 检查邮箱是否已被其他用户使用
            SysUser existing = userMapper.selectOne(
                    new QueryWrapper<SysUser>().eq("email", request.getEmail()).ne("id", userId)
            );
            if (existing != null) {
                throw new RuntimeException("邮箱已被其他用户使用");
            }
            user.setEmail(request.getEmail());
        }
        userMapper.updateById(user);
        log.info("用户 {} 信息已更新", userId);
    }

    @Override
    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        // 验证旧密码
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            throw new RuntimeException("旧密码错误");
        }
        // 加密新密码
        String encoded = passwordEncoder.encode(request.getNewPassword());
        user.setPassword(encoded);
        userMapper.updateById(user);
        log.info("用户 {} 密码已修改", userId);
    }

    @Override
    public SysUser findByUsernameOrEmail(String usernameOrEmail) {
        return userMapper.selectOne(
                new QueryWrapper<SysUser>().eq("username", usernameOrEmail).or().eq("email", usernameOrEmail)
        );
    }
    // 辅助方法：从Authentication中提取userId
    private Long getCurrentUserId(Authentication authentication) {
        String username = authentication.getName();
        SysUser user = userMapper.selectOne(
                new QueryWrapper<SysUser>().eq("username", username).or().eq("email", username)
        );
        if (user == null) {
            throw new RuntimeException("当前用户不存在");
        }
        return user.getId();
    }
}