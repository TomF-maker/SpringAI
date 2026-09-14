package com.example.springai.utils;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 客户端 IP 提取与网段归一化。
 *
 * <p><b>安全前提</b>：本应用目前**没有任何反向代理**（deploy.ps1 直连 :8080），
 * 因此 {@code X-Forwarded-For} 这类头是客户端可以随意伪造的。
 * 如果优先信任它，攻击者只要知道密码、再把受害者的历史 IP 填进去，
 * 就永远不会触发异地登录校验 —— 风控直接失效。
 *
 * <p>所以 {@code getRemoteAddr()} 才是权威来源；只有当直连对端地址出现在
 * {@code app.login.trusted-proxies} 白名单里时，才去读转发头。
 */
@Slf4j
@Component
public class IpUtils {

    /** 逗号分隔的可信代理地址列表；留空表示忽略所有转发头。 */
    @Value("${app.login.trusted-proxies:}")
    private String trustedProxiesConfig;

    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3}\\.){3}\\d{1,3}$");
    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9a-fA-F:.]+$");

    private static final String UNKNOWN = "unknown";

    /** 取客户端 IP。解析失败时返回 null，调用方应视作"无法判断"。 */
    public String getClientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String remote = request.getRemoteAddr();

        Set<String> trusted = trustedProxies();
        if (!trusted.isEmpty() && remote != null && trusted.contains(remote)) {
            String forwarded = firstValidForwarded(request.getHeader("X-Forwarded-For"));
            if (forwarded != null) {
                return forwarded;
            }
            String realIp = normalize(request.getHeader("X-Real-IP"));
            if (realIp != null) {
                return realIp;
            }
        }
        return normalize(remote);
    }

    /**
     * 把 IP 归一化成"网段令牌"，用于比较与存储。
     *
     * <p>IPv4 取 /24，IPv6 取 /64。IPv6 用 /64 而不是 /48 是有原因的：
     * ISP 通常给用户分配 /56 或 /64，/48 会被多个用户共享，既漏判又误判；
     * 而 RFC 4941 隐私扩展是在 /64 内部轮换接口标识，所以 /64 正好合适。
     *
     * @return 网段令牌；无法解析时返回 null
     */
    public String toPrefix(String ip) {
        InetAddress addr = parseLiteral(ip);
        if (addr == null) {
            return null;
        }
        byte[] b = addr.getAddress();
        if (b.length == 4) {
            return (b[0] & 0xff) + "." + (b[1] & 0xff) + "." + (b[2] & 0xff);
        }
        // 前 8 字节 = /64，按标准 4 组十六进制展示，不做零压缩以保证可比性
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i += 2) {
            if (sb.length() > 0) {
                sb.append(':');
            }
            sb.append(String.format("%02x%02x", b[i] & 0xff, b[i + 1] & 0xff));
        }
        return sb.toString();
    }

    /**
     * 两个网段令牌是否属于同一网段。
     *
     * <p><b>解析不出来时返回 true（fail-open）</b>：这是异常检查，不是密码检查，
     * 一个解析 bug 决不能把所有用户锁在门外。
     */
    public boolean sameNetwork(String a, String b) {
        if (a == null || b == null) {
            return true;
        }
        return a.equals(b);
    }

    // ==================== 内部方法 ====================

    private Set<String> trustedProxies() {
        if (trustedProxiesConfig == null || trustedProxiesConfig.isBlank()) {
            return Set.of();
        }
        Set<String> set = new HashSet<>();
        for (String s : trustedProxiesConfig.split(",")) {
            String v = s.trim();
            if (!v.isEmpty()) {
                set.add(v);
            }
        }
        return set;
    }

    /** XFF 是客户端可以逐跳追加的，只取第一个合法的非 unknown 值。 */
    private String firstValidForwarded(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        List<String> parts = Arrays.asList(header.split(","));
        for (String part : parts) {
            String candidate = part.trim();
            if (candidate.isEmpty() || UNKNOWN.equalsIgnoreCase(candidate)) {
                continue;
            }
            String normalized = normalize(candidate);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private String normalize(String ip) {
        InetAddress addr = parseLiteral(ip);
        if (addr == null) {
            return null;
        }
        if (addr.isLoopbackAddress() || addr.isAnyLocalAddress()) {
            return "127.0.0.1";
        }
        return addr.getHostAddress();
    }

    /**
     * 只接受 IP 字面量，绝不把攻击者可控的字符串交给 DNS 解析。
     * 先正则粗筛，再交给 InetAddress（此时已确保是字面量，不会发起查询）。
     */
    private InetAddress parseLiteral(String ip) {
        if (ip == null) {
            return null;
        }
        String v = ip.trim();
        if (v.isEmpty()) {
            return null;
        }
        boolean looksV4 = IPV4.matcher(v).matches();
        boolean looksV6 = v.indexOf(':') >= 0 && IPV6_CHARS.matcher(v).matches();
        if (!looksV4 && !looksV6) {
            return null;
        }
        try {
            // getByName 对 ::ffff:1.2.3.4 会自动折成 Inet4Address，正是我们要的
            return InetAddress.getByName(v);
        } catch (UnknownHostException e) {
            log.debug("IP 字面量解析失败: {}", ip);
            return null;
        }
    }
}
