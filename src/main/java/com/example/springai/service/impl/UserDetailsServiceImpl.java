package com.example.springai.service.impl;

import com.example.springai.entity.SysUser;
import com.example.springai.mapper.SysUserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    @Autowired
    private SysUserMapper userMapper;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // 从数据库查询用户（支持用户名、邮箱或手机号登录）
        SysUser sysUser = userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SysUser>()
                        .eq("username", username)
                        .or()
                        .eq("email", username)
        );

        if (sysUser == null) {
            throw new UsernameNotFoundException("用户不存在: " + username);
        }

        // 检查账号状态
        if (sysUser.getStatus() == 0) {
            throw new UsernameNotFoundException("账号已被禁用");
        }

        // 构建 Spring Security 的 UserDetails 对象
        return User.builder()
                .username(sysUser.getUsername())
                .password(sysUser.getPassword())
                .authorities(Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + getRoleCode(sysUser))))
                .disabled(sysUser.getStatus() == 0)
                .build();
    }

    /**
     * 根据用户获取角色编码（简化版，后续可扩展）
     */
    private String getRoleCode(SysUser user) {
        if (user.getIsAdmin() == 1) {
            return "ADMIN";
        }
        // 根据 user_type 返回不同角色
        if (user.getUserType() == 2) {
            return "GUEST";
        }
        return "USER";
    }
}