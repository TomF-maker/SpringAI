package com.example.springai.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.example.springai.common.MembershipPlan;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class SysUser {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String password;
    private String email;
    private String phone;
    private String realName;
    private String avatar;
    private Long departmentId;

    /**
     * 所属公司（{@code sys_company.id}）。注册必填；本功能上线前的老用户为 NULL。
     *
     * <p>公司与 {@link #departmentId} 是两件互不相干的事：公司决定"能检索到哪些资料"
     * （客户隔离）与"每公司多少人"的统计；而部门维度已经废弃
     * （见 doc/商业化方案.md「A2. 去掉部门维度」），{@code departmentId} 只是
     * 一个历史占位常量，不参与任何权限判断。
     *
     * <p><b>存 id 而不是公司名字符串</b>：第一版存的是字符串，结果信用代码的唯一约束
     * 落到用户表上，变成"一家公司只能注册一个人"。见 {@code SysCompany} 的类注释。
     */
    private Long companyId;

    private Integer userType;   // 1=内部 2=外部
    private Integer status;     // 0=禁用 1=启用

    /**
     * 是否管理员。**注意它只是一半条件，必须配合 {@link #isInternalAdmin()} /
     * {@link #isClientAdmin()} 一起看** —— 单看这一列无法判断"管理员"的作用范围。
     */
    private Integer isAdmin;    // 0=否 1=是

    /**
     * 是否必须先修改初始密码才能使用平台。1=必须改。
     *
     * <p>xlsx 批量导入的账号一律置 1 —— 那些账号的密码是配置里的**统一初始密码**，
     * 每一行都一样、还给到了导入者手上。不强制改的话，那个密码会一直有效，
     * 等于一批账号共用一个公开口令。
     *
     * <p>服务端拦在 {@code PasswordChangeGuardFilter}，不是靠前端跳转 ——
     * 前端只是引导，绕过它（直接调接口）才是真正要防的。
     */
    private Integer mustChangePassword;
    private LocalDateTime lastLoginTime;
    /** 上次登录的网段令牌（IPv4 /24 或 IPv6 /64），不是完整 IP。见 IpUtils.toPrefix */
    private String lastLoginIp;
    /** 积分余额。流水见 kb_points_log，两者要对得上。 */
    private Integer points;
    /** 会员档位（MembershipPlan 的枚举名）；NULL = 非会员。 */
    private String memberType;
    /** 会员到期时间；永久会员为 NULL。 */
    private LocalDateTime memberExpireAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 内部全局管理员（伯伯咨询顾问 + is_admin=1）。**可见所有客户的内容。**
     *
     * <p>判定必须带 {@code userType != 2}：`is_admin` 这一列本身不区分作用范围，
     * 客户公司的管理员也用同一个标志位。若只看 {@code isAdmin == 1}，
     * 一个外部客户管理员就会命中"管理员不过滤"的分支，看到<b>所有客户</b>的文档 ——
     * 这是咨询行业绝对不能出的越权。
     *
     * <p>老数据 {@code userType} 为 NULL 时按内部处理（与既有语义一致）。
     */
    public boolean isInternalAdmin() {
        return isAdmin != null && isAdmin == 1 && !isExternal();
    }

    /**
     * 客户公司管理员（外部用户 + is_admin=1）。**只能看本公司数据。**
     *
     * <p>作用是让客户方有个人能看本公司的使用情况、热门问题、员工活跃榜，
     * 而不用把全局管理权限发出去。
     */
    public boolean isClientAdmin() {
        return isAdmin != null && isAdmin == 1 && isExternal();
    }

    /** 是否外部用户（客户公司员工）。{@code userType=2} 是唯一的外部标志。 */
    public boolean isExternal() {
        return userType != null && userType == 2;
    }

    /**
     * 是否必须先改初始密码。null（老数据 / 列还没读到）一律按"不需要"处理。
     *
     * <p><b>方法名刻意不加 {@code is} 前缀。</b>字段 {@code mustChangePassword}
     * 已经由 Lombok 生成了 {@code getMustChangePassword(): Integer}，
     * 再加一个 {@code isMustChangePassword(): boolean} 就构成"同一属性两个类型不同的
     * getter"—— MyBatis 的反射会直接抛
     * {@code Illegal overloaded getter method with ambiguous type}，
     * <b>导致 SysUser 的所有查询与更新全部失败、登录直接 500</b>。
     * 这和 {@link #hasActiveMembership} 用 {@code has} 前缀是同一个道理。
     */
    public boolean mustChangePassword() {
        return mustChangePassword != null && mustChangePassword == 1;
    }

    /**
     * 会员是否有效。<b>这是唯一的判定处</b> —— 关卡、quota 接口、定时任务、
     * 个人中心卡片都必须调它，不要各写各的 {@code memberType != null}。
     *
     * <p>两条容易踩的边界：
     * <ul>
     *   <li><b>非永久档但 expireAt 为 NULL 判为「已过期」，绝不 NPE。</b>
     *       这种脏数据（类型在、日期被清）真实存在；写成 {@code expireAt.isAfter(now)}
     *       会在 /chat 里被 {@code catch (Exception)} 吞成"处理失败"并写一条假的
     *       HIT_ERROR 埋点（看板上凭空多出检索失败），在流式接口里则会让异常逃出
     *       返回 Flux 的方法、变成 JSON 错误体污染 SSE。</li>
     *   <li><b>必须要求 memberType 非空。</b>否则一行残留的 expireAt（类型清了、日期没清）
     *       就会白送会员。</li>
     * </ul>
     *
     * @param now 由调用方传入，便于测试；生产代码用 {@code LocalDateTime.now(ZONE)}
     */
    public boolean hasActiveMembership(LocalDateTime now) {
        if (memberType == null || memberType.isBlank()) {
            return false;
        }
        if (MembershipPlan.PERMANENT.name().equals(memberType)) {
            return true;
        }
        if (memberExpireAt == null) {
            return false;   // 脏数据：fail closed
        }
        return memberExpireAt.isAfter(now);
    }
}