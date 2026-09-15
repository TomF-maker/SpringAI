package com.example.springai.service.impl;

import com.example.springai.common.IpGeo;
import com.example.springai.common.RedisScripts;
import com.example.springai.service.IpGeoServiceI;
import com.example.springai.utils.IpUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 归属地解析里与服务商无关的那一半：缓存、限流、熔断、失败策略、私有地址短路。
 *
 * <p>子类只实现 {@link #fetch(String)}，负责"发 HTTP + 解析 body"。
 *
 * <p><b>为什么要有熔断器</b>：服务商挂了的时候，每一个没缓存的 IP 都会去试一次外呼，
 * 每次都等到超时才失败。加了熔断之后，连续失败到达阈值就整段时间跳过外呼，
 * 把"每个请求都卡 800ms"降成"直接返回 UNKNOWN"。
 *
 * <p><b>失败为什么不缓存</b>：把一次 10 分钟的网络抖动缓存成 30 天的数据缺口，
 * 而且恰好固化在最热的那些 IP 上。per-IP 的缓存只放 per-IP 的答案；
 * 全局性的故障用全局熔断器表达。
 */
@Slf4j
public abstract class AbstractIpGeoService implements IpGeoServiceI {

    private static final String CACHE_PREFIX = "geo:ip:";
    private static final String RATE_PREFIX = "geo:rate:";
    private static final String CIRCUIT_KEY = "geo:circuit:fail";

    /** 限流窗口固定 60 秒，TTL 给 70 秒 —— 免得窗口还没走完 key 就过期、计数被重置。 */
    private static final long RATE_WINDOW_TTL_SECONDS = 70L;
    private static final long CIRCUIT_FAIL_TTL_SECONDS = 60L;

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    private IpUtils ipUtils;

    /** 缓存天数。归属地基本不变，可以放很久。 */
    @Value("${app.geo.cache-ttl-days:30}")
    private int cacheTtlDays;

    /** 每分钟外呼上限。<= 0 视为不限流。 */
    @Value("${app.geo.rate.per-minute:30}")
    private int ratePerMinute;

    /** 连续失败多少次后熔断。 */
    @Value("${app.geo.circuit.failure-threshold:3}")
    private int circuitFailureThreshold;

    protected final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    void logActiveProvider() {
        log.info("📍 IP归属地服务商: {}（app.geo.provider={}，限流 {}/分钟，缓存 {} 天）",
                getClass().getSimpleName(), providerName(), ratePerMinute, cacheTtlDays);
    }

    /** 用于启动日志和排查的服务商标识。 */
    protected abstract String providerName();

    /**
     * 真正去问服务商。
     *
     * <p><b>返回非 null 表示"这次外呼成功了"</b>，即使三个字段全空 ——
     * "查得到这个 IP 但库里没有它的省市"是**合法答案**，会被正常缓存，
     * 否则同一个境外 IP 每次提问都会白烧一次配额。
     *
     * <p>只有真正的故障（超时、5xx、body 解析失败、服务商报错）才允许抛异常，
     * 那才触发熔断计数且不缓存。
     */
    protected abstract IpGeo fetch(String ip) throws Exception;

    @Override
    public final IpGeo lookup(String ip) {
        // 1. 内网 / 保留地址：本地判断，绝不外呼
        if (ipUtils.isPrivate(ip)) {
            return IpGeo.UNKNOWN;
        }
        try {
            // 2. 缓存
            IpGeo cached = readCache(ip);
            if (cached != null) {
                return cached;
            }
            // 3. 熔断中：直接放弃，不浪费一次超时
            if (circuitOpen()) {
                return IpGeo.UNKNOWN;
            }
            // 4. 限流：必须在 HTTP 之前判，否则配额照样被打爆
            if (!tryAcquireQuota()) {
                return IpGeo.UNKNOWN;
            }
            // 5. 外呼
            IpGeo geo = fetch(ip);
            if (geo == null) {
                geo = IpGeo.UNKNOWN;
            }
            writeCache(ip, geo);
            clearFailures();
            return geo;
        } catch (Exception e) {
            recordFailure();
            log.warn("IP 归属地解析失败 ip={}: {}", ip, e.getMessage());
            return IpGeo.UNKNOWN;
        }
    }

    // ==================== 缓存 ====================

    /** @return null 表示"没有缓存"；非 null（哪怕字段全空）表示"缓存里有这个 IP 的答案" */
    private IpGeo readCache(String ip) {
        try {
            String json = redis.opsForValue().get(CACHE_PREFIX + ip);
            if (json == null) {
                return null;
            }
            JsonNode node = objectMapper.readTree(json);
            return new IpGeo(text(node, "country"), text(node, "province"), text(node, "city"));
        } catch (Exception e) {
            // 缓存读坏不该影响主流程，当作没缓存重查一次
            log.debug("归属地缓存读取失败 ip={}: {}", ip, e.getMessage());
            return null;
        }
    }

    private void writeCache(String ip, IpGeo geo) {
        try {
            String json = objectMapper.writeValueAsString(new CachedGeo(geo));
            redis.opsForValue().set(CACHE_PREFIX + ip, json, cacheTtl());
        } catch (Exception e) {
            log.debug("归属地缓存写入失败 ip={}: {}", ip, e.getMessage());
        }
    }

    /** TTL 加 ±10% 抖动：批量写入的键若同时过期，会一起穿透到服务商。 */
    private Duration cacheTtl() {
        long base = Duration.ofDays(Math.max(cacheTtlDays, 1)).getSeconds();
        long jitter = ThreadLocalRandom.current().nextLong(-base / 10, base / 10 + 1);
        return Duration.ofSeconds(base + jitter);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    // ==================== 限流 ====================

    private boolean tryAcquireQuota() {
        if (ratePerMinute <= 0) {
            return true;
        }
        long window = System.currentTimeMillis() / 60_000L;
        try {
            Long used = redis.execute(
                    new DefaultRedisScript<>(RedisScripts.INCR_WITH_TTL, Long.class),
                    List.of(RATE_PREFIX + window),
                    String.valueOf(RATE_WINDOW_TTL_SECONDS));
            return used != null && used <= ratePerMinute;
        } catch (Exception e) {
            // fail closed：Redis 不可用时恰恰是最不该去打扰外部服务的时候。
            // 注意这里不能照抄 DailyCounter.increment 返回 0 的"放行"语义。
            log.error("归属地限流计数失败，本次跳过外呼: {}", e.getMessage());
            return false;
        }
    }

    // ==================== 熔断 ====================

    private boolean circuitOpen() {
        try {
            String v = redis.opsForValue().get(CIRCUIT_KEY);
            return v != null && Long.parseLong(v) >= circuitFailureThreshold;
        } catch (Exception e) {
            // Redis 读不到就不熔断 —— 宁可多打几次外呼，也不要因为读不到状态而永久停摆
            return false;
        }
    }

    private void recordFailure() {
        try {
            redis.execute(
                    new DefaultRedisScript<>(RedisScripts.INCR_WITH_TTL, Long.class),
                    List.of(CIRCUIT_KEY),
                    String.valueOf(CIRCUIT_FAIL_TTL_SECONDS));
        } catch (Exception ignored) {
            // 记录失败都失败就只能算了
        }
    }

    private void clearFailures() {
        try {
            redis.delete(CIRCUIT_KEY);
        } catch (Exception ignored) {
            // 同上
        }
    }

    /** 缓存序列化用的扁平结构 —— 不直接序列化 {@link IpGeo}，免得它的展示方法进入线协议。 */
    private record CachedGeo(String country, String province, String city) {
        CachedGeo(IpGeo geo) {
            this(geo.getCountry(), geo.getProvince(), geo.getCity());
        }
    }

    /**
     * 建一个<b>带超时</b>的 RestTemplate。
     *
     * <p>刻意不用 {@code RestTemplateConfig} 里那个共享 Bean：它是裸的 {@code new RestTemplate()}，
     * 底层 {@code SimpleClientHttpRequestFactory} 的 connect/read timeout 默认都是 0 = <b>无限等待</b>。
     * 服务商一挂，调用线程就永久挂在 socket 上 —— 因为归属地是在专用单线程池里跑的，
     * 那等于整个功能静默停摆，没有任何告警。共享 Bean 不能改，短信和文档下载都在用。
     *
     * @param connectMs 建连超时
     * @param readMs    读超时
     */
    protected RestTemplate buildRestTemplate(int connectMs, int readMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectMs);
        factory.setReadTimeout(readMs);
        return new RestTemplate(factory);
    }
}
