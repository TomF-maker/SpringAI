package com.example.springai.service.impl;

import com.example.springai.entity.SysUser;
import com.example.springai.mapper.SysUserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

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
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + getRoleCode(sysUser)));
        // "必须先改初始密码"作为一个额外 authority 带出去。
        //
        // 为什么放在 authority 而不让过滤器再查一次库：JwtAuthenticationFilter 每个请求
        // 都会调本方法（authority 是**每次重新查库**得到的，不是签进 JWT 里的），
        // 所以这里加一个就够，不需要多一次 SELECT。
        // 也因此改完密码后**不需要重新登录** —— 下一次请求查到的就是"不需要改"了。
        if (sysUser.mustChangePassword()) {
            authorities.add(new SimpleGrantedAuthority(PWD_CHANGE_REQUIRED_AUTHORITY));
        }

        return User.builder()
                .username(sysUser.getUsername())
                .password(sysUser.getPassword())
                .authorities(authorities)
                .disabled(sysUser.getStatus() == 0)
                .build();
    }

    /**
     * "必须先改初始密码"的 authority 名。
     *
     * <p>与 {@code PasswordChangeGuardFilter} 共用，所以是 public 常量 ——
     * 两边各写一遍字符串，改一处漏一处就会静默失效（护栏还在，但认不出这个标记）。
     */
    public static final String PWD_CHANGE_REQUIRED_AUTHORITY = "PWD_CHANGE_REQUIRED";

    /**
     * 根据用户获取角色编码。
     *
     * <p><b>作用范围由 userType 决定，这是本方法的全部要点：</b>
     * <ul>
     *   <li>内部用户 + is_admin=1 → {@code ADMIN}（伯伯咨询顾问，可见所有客户）</li>
     *   <li>外部用户 + is_admin=1 → {@code CLIENT_ADMIN}（客户公司管理员，<b>只能看本公司</b>）</li>
     *   <li>外部用户 → {@code GUEST}</li>
     *   <li>其余 → {@code USER}</li>
     * </ul>
     *
     * <p>外部用户的判定<b>必须放在 is_admin 之前</b>。反过来的话，客户公司管理员
     * 拿到的是 ROLE_ADMIN，而 {@code /api/dashboard/**} 全是
     * {@code @PreAuthorize("hasRole('ADMIN')")} —— 等于把全部客户的数据看板
     * 送给了其中一家客户。同一个 {@code is_admin} 列，作用范围靠 userType 区分。
     *
     * <p>包级可见而不是 private：这条规则是客户隔离的一道闸门，
     * 必须能直接被单测钉住（同 {@code ConversationServiceImpl.isOwner} 的做法）。
     */
    String getRoleCode(SysUser user) {
        if (user.isExternal()) {
            // 外部用户永远拿不到 ADMIN，最多是本公司的 CLIENT_ADMIN
            return user.isClientAdmin() ? "CLIENT_ADMIN" : "GUEST";
        }
        if (user.getIsAdmin() != null && user.getIsAdmin() == 1) {
            return "ADMIN";
        }
        return "USER";
    }
}