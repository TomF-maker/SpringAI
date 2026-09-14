package com.example.springai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.springai.common.AppTime;
import com.example.springai.common.ErrorCode;
import com.example.springai.common.MembershipPlan;
import com.example.springai.dto.MembershipStatusDTO;
import com.example.springai.entity.KbMembershipLog;
import com.example.springai.entity.KbPointsLog;
import com.example.springai.entity.SysUser;
import com.example.springai.exception.BizException;
import com.example.springai.mapper.KbMembershipLogMapper;
import com.example.springai.mapper.SysUserMapper;
import com.example.springai.service.MembershipServiceI;
import com.example.springai.service.PointsServiceI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class MembershipService implements MembershipServiceI {

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private KbMembershipLogMapper membershipLogMapper;

    @Autowired
    private PointsServiceI pointsService;

    @Autowired
    private FreeQuestionQuotaLimiter freeQuotaLimiter;

    /** 用于让批循环里每条记录各自成一个事务（同类方法自调用不会开事务）。 */
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Override
    public MembershipStatusDTO getStatus(Long userId) {
        MembershipStatusDTO dto = new MembershipStatusDTO();
        dto.setUserId(userId);
        if (userId == null) {
            return dto;
        }
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            return dto;
        }

        LocalDateTime now = AppTime.now();
        dto.setPoints(user.getPoints() == null ? 0 : user.getPoints());
        dto.setMemberType(user.getMemberType());
        dto.setMemberExpireAt(user.getMemberExpireAt());

        MembershipPlan plan = MembershipPlan.parse(user.getMemberType());
        dto.setMemberTypeLabel(plan == null ? null : plan.getLabel());

        // 判定只走这一处
        boolean active = user.hasActiveMembership(now);
        dto.setActive(active);

        if (active && user.getMemberExpireAt() != null) {
            // 向上取整：还剩 3 小时也该显示"剩余 1 天"，显示 0 天会让人以为已经过期
            long days = (long) Math.ceil(
                    ChronoUnit.MINUTES.between(now, user.getMemberExpireAt()) / (60.0 * 24));
            dto.setRemainingDays(Math.max(days, 0));
        }

        if (!active) {
            dto.setFreeDailyLimit(freeQuotaLimiter.getPerUserDailyLimit());
            dto.setFreeRemaining(freeQuotaLimiter.remaining(userId));
        }
        return dto;
    }

    @Override
    public List<Map<String, Object>> listPlans() {
        List<Map<String, Object>> plans = new ArrayList<>();
        for (MembershipPlan plan : MembershipPlan.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", plan.name());
            item.put("label", plan.getLabel());
            item.put("durationLabel", plan.getDurationLabel());
            // 永久会员没有价格 —— 用 isExchangeable 挡在前面，不去调会抛异常的 pointsCost()
            item.put("exchangeable", plan.isExchangeable());
            item.put("pointsCost", plan.isExchangeable() ? plan.pointsCost() : null);
            plans.add(item);
        }
        return plans;
    }

    @Override
    @Transactional
    public MembershipStatusDTO exchange(Long userId, String planName) {
        MembershipPlan plan = MembershipPlan.parse(planName);
        if (plan == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择有效的会员档位");
        }
        if (!plan.isExchangeable()) {
            // 前端已置灰，但服务端必须也拒绝 —— 灰度只是 UX
            throw new BizException(ErrorCode.BAD_REQUEST, "永久会员不对积分开放，请联系管理员");
        }

        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        // 判档位，不判日期 —— 脏数据组合下判日期会误判
        if (MembershipPlan.PERMANENT.name().equals(user.getMemberType())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "已是永久会员，无需兑换");
        }

        int cost = plan.pointsCost();
        int have = user.getPoints() == null ? 0 : user.getPoints();
        if (have < cost) {
            // 这次预检**只为文案**。权威判断是下面 deduct 的返回值 ——
            // 并发下两个请求都通过预检是正常的，挡下来的是 SQL 里的 points >= ?
            throw new BizException(ErrorCode.BAD_REQUEST, "积分不足，还差 " + (cost - have) + " 分");
        }

        LocalDateTime now = AppTime.now();
        String oldType = user.getMemberType();
        LocalDateTime oldExpire = user.getMemberExpireAt();

        // 续期基准取 max(now, 现有到期时间)：用户没用完的时长不能被吃掉。
        // 顺带一个隐性好处 —— 即使 DB 里还挂着"已过期但未清理"的陈旧值，max 也会把它吃掉。
        LocalDateTime base = (oldExpire != null && oldExpire.isAfter(now)) ? oldExpire : now;
        LocalDateTime newExpire = plan.plusFrom(base);

        // 1. 先写会员日志拿自增 id —— 积分流水的 ref_id 要指向它，双向可追溯
        KbMembershipLog mlog = new KbMembershipLog();
        mlog.setUserId(userId);
        mlog.setAction(KbMembershipLog.ACTION_EXCHANGE);
        mlog.setMemberType(plan.name());
        mlog.setExpireAt(newExpire);
        mlog.setPointsCost(cost);
        mlog.setRemark("积分兑换" + plan.getLabel());
        membershipLogMapper.insert(mlog);

        // 2. 扣分。原子且有下限守卫；余额不足会抛异常，整个事务回滚
        pointsService.deduct(userId, cost, KbPointsLog.REASON_EXCHANGE_MEMBERSHIP,
                mlog.getId(), null, "兑换" + plan.getLabel());

        // 3. CAS 更新会员字段。并发时只有一个能命中，另一个回滚（含它的扣分）
        int affected = userMapper.casMembership(userId, plan.name(), newExpire, oldType, oldExpire);
        if (affected == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "操作冲突，请重试");
        }

        log.info("🎫 积分兑换会员: userId={}, 档位={}, 消耗={}, 新到期={}",
                userId, plan.name(), cost, newExpire);
        return getStatus(userId);
    }

    @Override
    @Transactional
    public void grant(Long userId, String planName, LocalDateTime expireAt,
                      Long operatorId, String remark) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }

        String oldType = user.getMemberType();
        LocalDateTime oldExpire = user.getMemberExpireAt();

        String newType = null;
        LocalDateTime newExpire = null;
        String label = "取消会员";

        if (planName != null && !planName.isBlank()) {
            MembershipPlan plan = MembershipPlan.parse(planName);
            if (plan == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "无效的会员档位: " + planName);
            }
            newType = plan.name();
            label = plan.getLabel();
            if (plan == MembershipPlan.PERMANENT) {
                // 永久会员必须把到期时间显式置 NULL。
                // 否则会留下 PERMANENT + 一个过去的日期，当晚的清理任务就把它当过期会员清掉，
                // 用户的永久会员凭空消失。这里传 null 会被写进 SET 子句（手写 SQL，不受
                // MyBatis-Plus「忽略 null 字段」的限制）。
                newExpire = null;
            } else {
                LocalDateTime base = (expireAt != null)
                        ? expireAt
                        : ((oldExpire != null && oldExpire.isAfter(AppTime.now())) ? oldExpire : AppTime.now());
                newExpire = plan.plusFrom(base);
            }
        }

        KbMembershipLog mlog = new KbMembershipLog();
        mlog.setUserId(userId);
        mlog.setAction(KbMembershipLog.ACTION_ADMIN_GRANT);
        mlog.setMemberType(newType);
        mlog.setExpireAt(newExpire);
        mlog.setPointsCost(0);
        mlog.setOperatorId(operatorId);
        mlog.setRemark(remark == null ? label : remark);
        membershipLogMapper.insert(mlog);

        int affected = userMapper.casMembership(userId, newType, newExpire, oldType, oldExpire);
        if (affected == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "操作冲突，请重试");
        }
        log.info("🎫 管理员调整会员: userId={}, 档位={}, 到期={}, 操作人={}",
                userId, newType, newExpire, operatorId);
    }

    @Override
    public int expireOverdue(int limit) {
        LocalDateTime cutoff = AppTime.now();

        // 谓词必须写完整：member_expire_at IS NOT NULL AND <= cutoff。
        // 只写 <= cutoff 其实是"碰巧安全"—— MySQL 里 NULL <= x 是 NULL、不匹配，
        // 所以永久会员碰巧不会被误清。这是运气不是设计，别让人"顺手简化"掉。
        List<SysUser> candidates = userMapper.selectList(new QueryWrapper<SysUser>()
                .select("id", "member_type", "member_expire_at")
                .isNotNull("member_type")
                .isNotNull("member_expire_at")
                .le("member_expire_at", cutoff)
                .last("LIMIT " + Math.max(1, limit)));

        int done = 0;
        for (SysUser candidate : candidates) {
            // 每条各自一个事务：批循环不能包在一个大事务里。
            // 用 TransactionTemplate 而不是给本类的方法加 @Transactional ——
            // 同类自调用不会走代理，事务不会生效。
            Boolean cleaned = transactionTemplate.execute(status -> expireOne(candidate));
            if (Boolean.TRUE.equals(cleaned)) {
                done++;
            }
        }
        if (done > 0) {
            log.info("🧹 会员到期清理完成: 处理 {} 条（候选 {} 条）", done, candidates.size());
        }
        return done;
    }

    /** 清理单条。幂等来源：CAS 命中才写 EXPIRE 日志。 */
    private boolean expireOne(SysUser user) {
        // CAS：只有到期时间与读到时一致才清。任务跑两遍、手动触发、两实例并发，
        // 第二次的 affected 都是 0，于是不会写第二条审计行。
        int affected = userMapper.casMembership(
                user.getId(), null, null, user.getMemberType(), user.getMemberExpireAt());
        if (affected == 0) {
            return false;
        }

        KbMembershipLog mlog = new KbMembershipLog();
        mlog.setUserId(user.getId());
        mlog.setAction(KbMembershipLog.ACTION_EXPIRE);
        mlog.setMemberType(null);
        mlog.setExpireAt(null);
        mlog.setPointsCost(0);
        mlog.setRemark("会员到期，已自动清理（原档位 " + user.getMemberType() + "）");
        membershipLogMapper.insert(mlog);
        return true;
    }
}
