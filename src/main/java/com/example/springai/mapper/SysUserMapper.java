package com.example.springai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.springai.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 原子地增减积分。
     *
     * <p>刻意用 {@code points = points + ?} 而不是"查出来、加一下、写回去" ——
     * 后者在并发下会丢更新（两个请求同时读到 100，各自写 150，实际应该是 200）。
     * InnoDB 会对这一行加锁，所以并发调用是串行的。
     *
     * @param delta 正数增加，负数消费
     * @return 受影响行数
     */
    @Update("UPDATE sys_user SET points = points + #{delta} WHERE id = #{userId}")
    int addPoints(@Param("userId") Long userId, @Param("delta") int delta);

    /**
     * 原子扣分，余额不足则不动任何数据。
     *
     * <p>{@code points >= #{cost}} 这个条件是正确性的唯一保证 —— 先查余额再扣会有并发窗口，
     * 两个请求都能读到"够扣"，最后扣成负数。
     *
     * <p><b>{@code cost} 必须为正数，调用方负责校验。</b>
     * 传负数会变成 {@code points - (-500)} 即<b>加钱</b>，而 {@code points >= -500} 恒真，
     * 于是返回 1 行、调用方以为扣款成功 —— 是实打实的资金 bug。
     * 服务层和这里都要挡（见 {@code PointsService.deduct}）。
     *
     * @return 受影响行数；0 表示余额不足或用户不存在
     */
    @Update("UPDATE sys_user SET points = points - #{cost} WHERE id = #{userId} AND points >= #{cost}")
    int deductPoints(@Param("userId") Long userId, @Param("cost") int cost);

    /**
     * 带旧值条件的会员字段更新（compare-and-set）。
     *
     * <p>用 {@code <=>}（MySQL 的 null-safe 等号）而不是 {@code =} ——
     * 非会员的 {@code member_type} 和永久会员的 {@code member_expire_at} 都是 NULL，
     * 而 {@code NULL = NULL} 在 SQL 里是 NULL、不匹配，CAS 会永远失败。
     *
     * <p>为什么需要 CAS：兑换是"读用户 → 算出新到期时间 → 写回"。
     * 两个并发请求会读到同一个旧值、算出同样的新值、先后写回同一个结果 ——
     * 用户被扣两次钱只拿到一个月。{@code UPDATE points} 的原子性只保护积分，保护不了这里。
     * CAS 失败 → 调用方回滚整个事务（连扣分一起退），所以还顺带让双击提交天然幂等。
     *
     * <p>{@code newType}/{@code newExpire} 传 null 表示清空会员（到期清理就走这条路径）——
     * 这里能用 null 是因为是手写 SQL；换成 {@code updateById} 是不行的，
     * MyBatis-Plus 默认忽略 null 字段（{@code LoginSecurityService.resetUser} 踩过并留了注释）。
     *
     * @return 受影响行数；0 表示有人并发改过
     */
    @Update("""
            UPDATE sys_user
               SET member_type = #{newType}, member_expire_at = #{newExpire}
             WHERE id = #{userId}
               AND member_type <=> #{oldType}
               AND member_expire_at <=> #{oldExpire}
            """)
    int casMembership(@Param("userId") Long userId,
                      @Param("newType") String newType,
                      @Param("newExpire") LocalDateTime newExpire,
                      @Param("oldType") String oldType,
                      @Param("oldExpire") LocalDateTime oldExpire);
}
