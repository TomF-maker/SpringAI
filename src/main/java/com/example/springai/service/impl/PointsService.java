package com.example.springai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.springai.common.ErrorCode;
import com.example.springai.entity.KbPointsLog;
import com.example.springai.entity.SysUser;
import com.example.springai.exception.BizException;
import com.example.springai.mapper.KbPointsLogMapper;
import com.example.springai.mapper.SysUserMapper;
import com.example.springai.service.PointsServiceI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 积分账户实现。
 *
 * <p>余额与流水必须一起变：{@code sys_user.points} 是对外展示的余额，
 * {@code kb_points_log} 是对账依据。只改余额不记流水，之后一旦对不上就查不出来，
 * 所以两者在同一个事务里，且**回写流水的逻辑两条路径共用**（{@link #writeLedger}），
 * 否则 balance_after 的算法、remark 截断长度迟早会分叉。
 */
@Slf4j
@Service
public class PointsService implements PointsServiceI {

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private KbPointsLogMapper pointsLogMapper;

    @Override
    @Transactional
    public int award(Long userId, int amount, String reason, Long refId,
                     Long operatorId, String remark) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (amount < 0) {
            // 显式挡掉：award 走的是无下限的 points + ?，负数能把余额扣成负数。
            // 曾经这是个潜伏 bug —— REASON_EXCHANGE_MEMBERSHIP 常量已存在，
            // 谁顺手拿它调 award 就能白送会员。
            throw new IllegalArgumentException("award 不接受负数，消费请走 deduct()");
        }
        if (amount == 0) {
            return getBalance(userId);
        }

        int affected = userMapper.addPoints(userId, amount);
        if (affected == 0) {
            throw new RuntimeException("用户不存在: " + userId);
        }
        return writeLedger(userId, amount, reason, refId, operatorId, remark);
    }

    @Override
    @Transactional
    public int deduct(Long userId, int amount, String reason, Long refId,
                      Long operatorId, String remark) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (amount <= 0) {
            // 传负数会变成加钱，且 points >= 负数 恒真 → 返回 1 行、调用方以为扣款成功。
            // mapper 里也挡了一道，两处都要有。
            throw new IllegalArgumentException("扣分必须为正数，实际: " + amount);
        }

        // 带 points >= ? 守卫的原子扣减。这是正确性的唯一保证 ——
        // 先查余额再扣会有并发窗口，两个请求都读到"够扣"，最后扣成负数。
        int affected = userMapper.deductPoints(userId, amount);
        if (affected == 0) {
            // 调用方（兑换流程）已经确认用户存在，所以这里只可能是余额不足
            throw new BizException(ErrorCode.BAD_REQUEST, "积分不足");
        }
        return writeLedger(userId, -amount, reason, refId, operatorId, remark);
    }

    /**
     * 变更已落库之后：回读余额并写流水。加分/扣分共用。
     *
     * <p>余额必须是**回读**的，不能用"预检余额 ± amount"算 —— 并发下会写错对账依据。
     * 同一事务内 UPDATE 已持有该行锁，所以读到的就是本次变更后的值。
     */
    private int writeLedger(Long userId, int changeAmount, String reason, Long refId,
                            Long operatorId, String remark) {
        SysUser user = userMapper.selectById(userId);
        int balanceAfter = (user == null || user.getPoints() == null) ? 0 : user.getPoints();

        KbPointsLog logRow = new KbPointsLog();
        logRow.setUserId(userId);
        logRow.setChangeAmount(changeAmount);
        logRow.setBalanceAfter(balanceAfter);
        logRow.setReason(reason);
        logRow.setRefId(refId);
        logRow.setOperatorId(operatorId);
        logRow.setRemark(truncate(remark, 255));
        pointsLogMapper.insert(logRow);

        log.info("💰 积分变更: userId={}, 变动={}, 余额={}, 原因={}, refId={}",
                userId, changeAmount, balanceAfter, reason, refId);
        return balanceAfter;
    }

    @Override
    public int getBalance(Long userId) {
        if (userId == null) {
            return 0;
        }
        SysUser user = userMapper.selectById(userId);
        if (user == null || user.getPoints() == null) {
            return 0;
        }
        return user.getPoints();
    }

    @Override
    public Page<KbPointsLog> listLogs(Long userId, int page, int size) {
        Page<KbPointsLog> pageParam = new Page<>(page, size);
        QueryWrapper<KbPointsLog> wrapper = new QueryWrapper<>();
        if (userId != null) {
            wrapper.eq("user_id", userId);
        }
        wrapper.orderByDesc("created_at", "id");
        return pointsLogMapper.selectPage(pageParam, wrapper);
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
