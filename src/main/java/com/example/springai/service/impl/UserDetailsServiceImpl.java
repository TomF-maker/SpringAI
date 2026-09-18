package com.example.springai.service.impl;

import com.example.springai.entity.SysCompany;
import com.example.springai.entity.SysUser;
import com.example.springai.mapper.SysCompanyMapper;
import com.example.springai.mapper.SysUserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private SysCompanyMapper companyMapper;

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

        // 所属公司停用 / 合约到期 → 一并拦下（A5 套餐硬拦）。
        //
        // 为什么放在这里而不是在登录接口里判一次：**授权信息是每个请求重新查库得到的**
        // （见下面 mustChangePassword 的注释），token 有效期是 12 小时 ——
        // 只在登录时判的话，客户被停用后最多还能继续用 12 小时。
        //
        // 抛 DisabledException（AuthenticationException 的子类）而不是
        // UsernameNotFoundException：后者会被 DaoAuthenticationProvider 统一
        // 伪装成"用户名或密码错误"，客户就不知道是欠费了；前者会原样透出我们的
        // 话术，登录接口把它转成明确的提示（AuthController.login 的 catch）。
        //
        // 顺带说明**已知的取舍**：这个判断在密码校验之前，所以知道某个用户名的人
        // 能看出"这家公司停用了"。我们接受这点信息暴露 —— 欠费提示必须让客户的
        // 员工看得懂，否则他们会去找 IT 改密码，反而更费事。
        String companyBlocked = blockedCompanyReason(sysUser);
        if (companyBlocked != null) {
            throw new DisabledException(companyBlocked);
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
     * 用户所属公司是否被拦下（停用 / 合约到期），返回原因；正常返回 null。
     *
     * <p>判定逻辑在 {@link SysCompany#blockReason} —— 只写一份，登录、每个请求、
     * 批量开户三处共用（三处各写一遍必然有一天只改一处）。
     *
     * <p><b>代价说明</b>：本方法每次请求都会多一次 {@code sys_company} 主键查询
     * （外部账号才有）。这是故意的取舍 —— token 有效期 12 小时，只在登录时判
     * 就意味着客户欠费后还能用半天。真到了查询压力显现的时候，可以在 Redis 里缓存
     * 几十秒（停用生效延迟几十秒是可接受的），但不要退回"只在登录时判"。
     *
     * <p>两种"查不到就不拦"的情况：账号没挂公司（内部账号，含 admin），
     * 或者公司行被删了（脏数据）。**不能让数据问题变成"客户登不进去"** ——
     * 那种故障最难查，客户还以为是我们停了他的服务。
     */
    private String blockedCompanyReason(SysUser user) {
        if (user.getCompanyId() == null) {
            return null;
        }
        SysCompany company = companyMapper.selectById(user.getCompanyId());
        if (company == null) {
            return null;
        }
        return company.blockReason(LocalDateTime.now());
    }

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