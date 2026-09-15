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
     * <p>公司与 {@link #departmentId} 是两件互不相干的事：公司用于"每公司多少人"的统计
     * 与展示，部门用于文档可见性等权限判断。注册时会拿公司名去 {@code sys_department}
     * 里碰一次（碰上了就把 departmentId 指过去），碰不上就挂到总公司。
     *
     * <p><b>存 id 而不是公司名字符串</b>：第一版存的是字符串，结果信用代码的唯一约束
     * 落到用户表上，变成"一家公司只能注册一个人"。见 {@code SysCompany} 的类注释。
     */
    private Long companyId;

    private Integer userType;   // 1=内部 2=外部
    private Integer status;     // 0=禁用 1=启用
    private Integer isAdmin;    // 0=否 1=是
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