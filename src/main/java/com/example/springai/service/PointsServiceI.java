package com.example.springai.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.springai.entity.KbPointsLog;

/**
 * 积分账户与流水。
 *
 * <p>加分走 {@link #award}、消费走 {@link #deduct}，两条路径都会在同一个事务里
 * 写一条 {@code kb_points_log} —— 余额和流水必须一起变，只有流水才能对账。
 */
public interface PointsServiceI {

    /**
     * 增加积分并记一条流水。
     *
     * @param reason 见 {@link KbPointsLog} 里的 REASON_* 常量
     * @return 变更后的余额
     * @throws IllegalArgumentException amount 为负（消费请走 {@link #deduct}）
     */
    int award(Long userId, int amount, String reason, Long refId, Long operatorId, String remark);

    /**
     * 消费积分并记一条流水。余额不足时整个操作失败、余额不变。
     *
     * <p>与 award 分开是刻意的：{@code award} 走的是无下限的
     * {@code points + ?}，负数能扣成负余额。消费必须走带
     * {@code points >= ?} 守卫的那条 SQL。
     *
     * @param amount 必须是正数
     * @return 变更后的余额
     * @throws com.example.springai.exception.BizException 余额不足
     */
    int deduct(Long userId, int amount, String reason, Long refId, Long operatorId, String remark);

    /** 当前余额。用户不存在时返回 0。 */
    int getBalance(Long userId);

    /** 某个用户的积分流水，按时间倒序。 */
    Page<KbPointsLog> listLogs(Long userId, int page, int size);
}
