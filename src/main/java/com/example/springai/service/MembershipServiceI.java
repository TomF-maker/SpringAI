package com.example.springai.service;

import com.example.springai.dto.MembershipStatusDTO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 会员：状态、积分兑换、运营发放、到期清理。
 *
 * <p><b>是否有效的判定统一走 {@code SysUser.hasActiveMembership}</b>，
 * 不要在各个调用点各写一遍。
 */
public interface MembershipServiceI {

    /** 会员状态 + 积分 + 免费额度。 */
    MembershipStatusDTO getStatus(Long userId);

    /** 可兑换档位，给前端渲染按钮：[{type, label, durationLabel, pointsCost, exchangeable}]。 */
    List<Map<String, Object>> listPlans();

    /**
     * 用积分兑换会员。扣分、改会员、写两份日志在同一个事务里完成。
     *
     * @param planName {@link com.example.springai.common.MembershipPlan} 的枚举名
     * @return 兑换后的状态
     */
    MembershipStatusDTO exchange(Long userId, String planName);

    /**
     * 管理员发放/调整会员。
     *
     * @param planName 档位名；传 null 表示取消会员
     * @param expireAt 指定到期时间；为 null 时按档位时长从当前时间推算。
     *                 <b>永久会员一律忽略它并置 NULL</b>
     */
    void grant(Long userId, String planName, LocalDateTime expireAt, Long operatorId, String remark);

    /**
     * 清理已到期的非永久会员：清空会员字段并记 EXPIRE 日志。
     *
     * <p><b>这个方法不是权限判定的依据</b> —— 权限看的是请求时的惰性判定
     * ({@code hasActiveMembership})。这里只是数据卫生：让管理页不撒谎、
     * 落审计、以及消除 {@code member_type != null} 被误读成"是会员"的坑。
     * 即使它从未执行，功能也完全正确。
     *
     * @param limit 单次处理上限，避免一次扫全表
     * @return 实际清理的条数
     */
    int expireOverdue(int limit);
}
