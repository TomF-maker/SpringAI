package com.example.springai.service.impl;

import com.example.springai.entity.SysUser;
import com.example.springai.mapper.SysUserMapper;
import com.example.springai.service.LoginSecurityServiceI;
import com.example.springai.service.SmsScene;
import com.example.springai.service.SmsServiceI;
import com.example.springai.utils.IpUtils;
import com.example.springai.utils.PhoneUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 异地登录风控实现。
 *
 * <p>设计要点（每一条都对应一个具体的攻击面）：
 * <ul>
 *   <li><b>验证码限尝试次数</b>：威胁模型里的攻击者本来就知道密码、challengeId 也是
 *       设计上发给他的。6 位码不限次数就是 10^6 次爆破，300 秒内足以撞开。
 *       所以失败 5 次直接销毁挑战，且重发短信不重置计数。</li>
 *   <li><b>兑换一次性</b>：用 SETNX 做单飞标记，避免同一 challengeId 被并发兑换两次。</li>
 *   <li><b>challengeId 用 CSPRNG</b>：UUID.randomUUID 走 SecureRandom；
 *       不要用 Hutool 的 RandomUtil（ThreadLocalRandom，非密码学安全）。</li>
 *   <li><b>重新校验账号状态</b>：/api/auth/** 整体放行，兑换接口绕过了
 *       AuthenticationManager，也就绕过了 UserDetailsServiceImpl 里的禁用检查。
 *       签发到兑换之间最长有 5 分钟，管理员可能在这期间禁用了账号。</li>
 * </ul>
 */
@Slf4j
@Service
public class LoginSecurityService implements LoginSecurityServiceI {

    private static final String CHALLENGE_PREFIX = "login:challenge:";
    private static final String ATTEMPT_PREFIX = "login:challenge:attempts:";
    private static final String USED_PREFIX = "login:challenge:used:";
    private static final String PREFIX_LIST_PREFIX = "login:ippref:";

    private static final long CHALLENGE_TTL_MINUTES = 5;
    private static final int MAX_ATTEMPTS = 5;
    private static final int MAX_TRUSTED_PREFIXES = 3;
    private static final long PREFIX_TTL_DAYS = 30;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private SmsServiceI smsService;

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private IpUtils ipUtils;

    /**
     * 异地登录校验总开关。默认关闭：存量账号大多没绑手机号，打开后会被强制补绑，
     * 而短信通道默认是 log 模式（收不到真实验证码）。
     */
    @Value("${app.login.ip-check.enabled:false}")
    private boolean ipCheckEnabled;

    /**
     * 短信总开关。关闭时**必须**不做任何挑战 —— 挑战需要用户收短信才能通过，
     * 短信发不出去的话，一旦触发挑战就等于把账号锁死。
     */
    @Value("${app.sms.enabled:false}")
    private boolean smsEnabled;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Decision decide(SysUser user, String currentIpPrefix) {
        // 短信发不出去时不能挑战：用户拿不到验证码，只会被锁在门外
        if (!ipCheckEnabled || !smsEnabled) {
            return Decision.allow();
        }
        // 首次登录（或本功能上线后的第一次登录）不挑战，只记录
        if (user.getLastLoginIp() == null) {
            return Decision.allow();
        }
        String boundPhone = PhoneUtils.normalize(user.getPhone());

        // 网段在白名单里，或与上次一致 → 放行
        if (isTrustedPrefix(user.getId(), currentIpPrefix)
                || ipUtils.sameNetwork(user.getLastLoginIp(), currentIpPrefix)) {
            return Decision.allow();
        }

        // 网络变了，需要二次验证。没绑手机号的只能先补绑。
        return boundPhone == null
                ? Decision.challenge(SCENE_BIND)
                : Decision.challenge(SCENE_VERIFY);
    }

    @Override
    public void markLoginSuccess(Long userId, String ipPrefix, String bindPhone) {
        if (bindPhone != null) {
            SysUser user = userMapper.selectById(userId);
            user.setPhone(bindPhone);
            userMapper.updateById(user);
        }
        if (ipPrefix != null) {
            trustPrefix(userId, ipPrefix);
            SysUser update = new SysUser();
            update.setId(userId);
            update.setLastLoginIp(ipPrefix);
            userMapper.updateById(update);
        }
    }

    @Override
    public String createChallenge(Long userId, String scene, String ipPrefix) {
        String challengeId = UUID.randomUUID().toString();
        Challenge record = new Challenge();
        record.setUserId(userId);
        record.setScene(scene);
        record.setIpPrefix(ipPrefix);
        record.setIssuedAt(System.currentTimeMillis());
        try {
            redis.opsForValue().set(CHALLENGE_PREFIX + challengeId,
                    objectMapper.writeValueAsString(record),
                    CHALLENGE_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            throw new RuntimeException("创建登录挑战失败", e);
        }
        return challengeId;
    }

    @Override
    public ChallengeInfo getChallenge(String challengeId) {
        Challenge record = readChallenge(challengeId);
        return record == null ? null : new ChallengeInfo(record.getUserId(), record.getScene());
    }

    @Override
    public RedeemResult redeem(String challengeId, String code, String newPhone, String currentIpPrefix) {
        if (challengeId == null || challengeId.isBlank() || code == null || code.isBlank()) {
            return RedeemResult.fail("验证信息不完整");
        }

        Challenge record = readChallenge(challengeId);
        if (record == null) {
            return RedeemResult.fail("验证已过期，请重新登录");
        }

        // 挑战与发起时的网段绑定（绑网段而不是精确 IP，避免移动端中途切换误伤）
        if (!ipUtils.sameNetwork(record.getIpPrefix(), currentIpPrefix)) {
            log.warn("挑战网段不匹配，userId={}, 期望={}, 实际={}",
                    record.getUserId(), record.getIpPrefix(), currentIpPrefix);
            return RedeemResult.fail("网络环境已变化，请重新登录");
        }

        // 账号状态必须重新校验 —— 这个接口绕过了 AuthenticationManager
        SysUser user = userMapper.selectById(record.getUserId());
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            return RedeemResult.fail("账号不存在或已被禁用");
        }

        String boundPhone = PhoneUtils.normalize(user.getPhone());
        String targetPhone;
        String smsScene;
        if (SCENE_BIND.equals(record.getScene())) {
            String provided = PhoneUtils.normalize(newPhone);
            if (provided == null) {
                return RedeemResult.fail("请填写正确的手机号");
            }
            if (isPhoneTakenByOther(provided, user.getId())) {
                return RedeemResult.fail("该手机号已被其他账号绑定");
            }
            targetPhone = provided;
            smsScene = SmsScene.BIND;
        } else {
            if (boundPhone == null) {
                return RedeemResult.fail("账号未绑定手机号");
            }
            targetPhone = boundPhone;
            smsScene = SmsScene.LOGIN;
        }

        if (!checkAttempts(challengeId)) {
            return RedeemResult.fail("验证失败次数过多，请重新登录");
        }

        if (!smsService.verifyCode(targetPhone, smsScene, code)) {
            return RedeemResult.fail("验证码错误或已过期");
        }

        // 单飞：同一 challengeId 只允许兑换成功一次
        Boolean first = redis.opsForValue().setIfAbsent(
                USED_PREFIX + challengeId, "1", CHALLENGE_TTL_MINUTES, TimeUnit.MINUTES);
        if (!Boolean.TRUE.equals(first)) {
            return RedeemResult.fail("该验证已使用，请重新登录");
        }

        redis.delete(CHALLENGE_PREFIX + challengeId);
        redis.delete(ATTEMPT_PREFIX + challengeId);
        return RedeemResult.ok(user);
    }

    @Override
    public void resetUser(Long userId) {
        redis.delete(PREFIX_LIST_PREFIX + userId);
        // MyBatis-Plus 默认忽略 null 字段，所以置空必须用 UpdateWrapper 显式 set
        userMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<SysUser>()
                        .eq("id", userId)
                        .set("last_login_ip", null));
        log.info("已清除用户 {} 的异地登录状态", userId);
    }

    // ==================== 内部方法 ====================

    private boolean checkAttempts(String challengeId) {
        String key = ATTEMPT_PREFIX + challengeId;
        Long attempts = redis.opsForValue().increment(key);
        if (attempts != null && attempts == 1L) {
            redis.expire(key, CHALLENGE_TTL_MINUTES, TimeUnit.MINUTES);
        }
        if (attempts != null && attempts > MAX_ATTEMPTS) {
            redis.delete(CHALLENGE_PREFIX + challengeId);
            redis.delete(key);
            log.warn("挑战 {} 验证码尝试次数超限，已销毁", challengeId);
            return false;
        }
        return true;
    }

    private Challenge readChallenge(String challengeId) {
        String json = redis.opsForValue().get(CHALLENGE_PREFIX + challengeId);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Challenge.class);
        } catch (Exception e) {
            log.warn("挑战记录解析失败: {}", e.getMessage());
            return null;
        }
    }

    private boolean isTrustedPrefix(Long userId, String prefix) {
        if (prefix == null) {
            return false;
        }
        List<String> known = redis.opsForList().range(PREFIX_LIST_PREFIX + userId, 0, -1);
        return known != null && known.contains(prefix);
    }

    /** 记录网段：用 LIST 保序，LTRIM 只留最近 3 个，TTL 每次成功登录滑动续期。 */
    private void trustPrefix(Long userId, String prefix) {
        String key = PREFIX_LIST_PREFIX + userId;
        List<String> known = redis.opsForList().range(key, 0, -1);
        if (known != null && known.contains(prefix)) {
            redis.opsForList().remove(key, 0, prefix);
        }
        redis.opsForList().leftPush(key, prefix);
        redis.opsForList().trim(key, 0, MAX_TRUSTED_PREFIXES - 1);
        redis.expire(key, PREFIX_TTL_DAYS, TimeUnit.DAYS);
    }

    private boolean isPhoneTakenByOther(String phone, Long selfUserId) {
        return userMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SysUser>()
                        .eq("phone", phone)
                        .ne("id", selfUserId)) > 0;
    }

    @Data
    public static class Challenge {
        private Long userId;
        private String scene;
        private String ipPrefix;
        private long issuedAt;
    }
}
