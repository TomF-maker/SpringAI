package com.example.springai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.springai.common.IpGeo;
import com.example.springai.entity.KbLoginLog;
import com.example.springai.entity.SysUser;
import com.example.springai.mapper.KbLoginLogMapper;
import com.example.springai.service.IpGeoServiceI;
import com.example.springai.service.LoginLogServiceI;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 登录审计。归属地**同步**解析 —— 登录本来就要跑一次 bcrypt（~100ms）
 * 加两三次数据库写，多几十毫秒无感，换来的是"一次 INSERT 带齐归属地"，
 * 不需要像提问那样再起一个异步补写。
 */
@Slf4j
@Service
public class LoginLogService implements LoginLogServiceI {

    @Autowired
    private KbLoginLogMapper loginLogMapper;

    @Autowired
    private IpGeoServiceI ipGeoService;

    private final AtomicBoolean schemaWarned = new AtomicBoolean(false);

    /**
     * 启动探针：确认表和列都在。
     *
     * <p>{@code doc/schema.sql} 里 {@code kb_login_log} 标了【阻塞项】——
     * 先建表再部署新 jar。忘了的话，写日志会一直失败，但 {@link #record} 刻意吞掉了异常
     * （不能让审计把登录打死），于是**故障会完全静默**。这条探针就是把这个静默
     * 变成开机可见的一行红字，成本和一个 SELECT 差不多。
     */
    @PostConstruct
    void verifySchema() {
        try {
            // 用 selectList 而不是 selectCount：COUNT(*) 不引用实体列清单，
            // 表在但列缺（比如 DDL 只执行了一半）时是查不出来的。
            loginLogMapper.selectList(new QueryWrapper<KbLoginLog>().last("LIMIT 1"));
            log.info("📍 登录审计表 kb_login_log 就绪");
        } catch (Exception e) {
            log.error("❌ kb_login_log 不可用，登录审计将静默失败。"
                    + "请先执行 doc/schema.sql 里的 2026-09-15 段建表语句: {}", e.getMessage());
        }
    }

    @Override
    public void record(SysUser user, String loginType, String clientIp, String ipPrefix) {
        if (user == null) {
            return;
        }
        try {
            KbLoginLog row = new KbLoginLog();
            row.setUserId(user.getId());
            row.setUsername(user.getUsername());
            row.setLoginType(loginType);
            row.setIpAddress(clientIp);
            row.setIpPrefix(ipPrefix);

            // lookup 的契约是"永不抛异常、永不返回 null"，所以这里不用再兜
            IpGeo geo = ipGeoService.lookup(clientIp);
            row.setIpCountry(geo.getCountry());
            row.setIpProvince(geo.getProvince());
            row.setIpCity(geo.getCity());

            loginLogMapper.insert(row);
        } catch (Throwable t) {
            // 捕获 Throwable 而不是 Exception，和 QuestionLogService.record 同构。
            // 这里尤其不能漏：issueToken 是 login / verifyLogin 的唯一收口点，
            // 让它抛出就是把所有人挡在门外。审计日志坏掉的代价必须小于登录不可用。
            if (schemaWarned.compareAndSet(false, true)) {
                log.error("❌ 登录审计写入失败（不影响登录），后续同类错误降为 debug。"
                        + "多半是 kb_login_log 没建表，见 doc/schema.sql 的 2026-09-15 段", t);
            } else {
                log.debug("登录审计写入失败: {}", t.getMessage());
            }
        }
    }
}
