package com.example.springai.service;

import com.example.springai.entity.SysUser;

/**
 * 异地登录风控：网段白名单 + 手机验证码挑战。
 */
public interface LoginSecurityServiceI {

    /** 挑战场景常量。 */
    String SCENE_VERIFY = "VERIFY";
    String SCENE_BIND = "BIND";

    /**
     * 判断本次登录是否可以直接放行。
     *
     * @param user          已通过密码校验的用户
     * @param currentIpPrefix 本次登录的网段令牌（可能为 null，表示无法判断）
     */
    Decision decide(SysUser user, String currentIpPrefix);

    /**
     * 登录成功后记录网段：写入白名单（最多保留 3 个）并刷新 last_login_ip。
     *
     * @param bindPhone 非 null 表示同时补绑该手机号（BIND 场景）
     */
    void markLoginSuccess(Long userId, String ipPrefix, String bindPhone);

    /** 创建挑战，返回 challengeId。 */
    String createChallenge(Long userId, String scene, String ipPrefix);

    /**
     * 读取挑战信息（不消费）。用于发验证码时在服务端解析接收号码，
     * 避免把完整手机号回传给前端。
     *
     * @return 不存在或已过期时返回 null
     */
    ChallengeInfo getChallenge(String challengeId);

    /** 消费挑战并校验验证码。 */
    RedeemResult redeem(String challengeId, String code, String newPhone, String currentIpPrefix);

    /** 管理员逃生通道：清除某用户的异地登录状态。 */
    void resetUser(Long userId);

    /** 挑战的只读视图。 */
    class ChallengeInfo {
        private final Long userId;
        private final String scene;

        public ChallengeInfo(Long userId, String scene) {
            this.userId = userId;
            this.scene = scene;
        }

        public Long getUserId() {
            return userId;
        }

        public String getScene() {
            return scene;
        }
    }

    /** 登录决策。 */
    class Decision {
        private final boolean allowed;
        private final String scene;

        private Decision(boolean allowed, String scene) {
            this.allowed = allowed;
            this.scene = scene;
        }

        public static Decision allow() {
            return new Decision(true, null);
        }

        public static Decision challenge(String scene) {
            return new Decision(false, scene);
        }

        public boolean isAllowed() {
            return allowed;
        }

        public String getScene() {
            return scene;
        }
    }

    /** 兑换结果。 */
    class RedeemResult {
        private final boolean success;
        private final String message;
        private final SysUser user;

        private RedeemResult(boolean success, String message, SysUser user) {
            this.success = success;
            this.message = message;
            this.user = user;
        }

        public static RedeemResult ok(SysUser user) {
            return new RedeemResult(true, null, user);
        }

        public static RedeemResult fail(String message) {
            return new RedeemResult(false, message, null);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public SysUser getUser() {
            return user;
        }
    }
}
