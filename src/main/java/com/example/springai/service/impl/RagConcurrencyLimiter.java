package com.example.springai.service.impl;

import com.example.springai.exception.BizException;
import com.example.springai.service.RagConcurrencyLimiterI;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 提问并发闸门。实现见 {@link RagConcurrencyLimiterI} 的类注释。
 *
 * <p><b>释放必须幂等</b>：{@code Semaphore.release()} 不像锁那样记得是谁拿的 ——
 * 多释放一次就会让许可数超过上限，而且**永远不会自愈**（后面的请求会一直超发）。
 * 流式路径里 {@code doFinally} 只触发一次，但把它写成幂等的成本极低，
 * 而写错的代价是一个只在长时间运行后才显现的、越来越松的闸门。
 */
@Slf4j
@Component
public class RagConcurrencyLimiter implements RagConcurrencyLimiterI {

    /** 全局同时进行的提问数上限。 */
    @Value("${app.rag.concurrency.max-concurrent:2}")
    private int maxConcurrent;

    /** 拿不到名额时最多排队等多久；等不到就拒绝，不无限挂着占用 Tomcat 线程。 */
    @Value("${app.rag.concurrency.queue-timeout-ms:10000}")
    private long queueTimeoutMs;

    private Semaphore slots;

    /**
     * 正在提问的身份（已登录＝{@code u:<id>}，匿名＝{@code ip:<ip>}）。
     *
     * <p>用 {@code ConcurrentHashMap.newKeySet()} 而不是普通 Set：
     * 判断"在不在"和"加进去"必须是一次原子操作，否则两个人同时进来会双双通过。
     */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    @PostConstruct
    void init() {
        int permits = Math.max(1, maxConcurrent);
        // fair=true：先到先得，避免后来的请求插队把先来的饿死
        this.slots = new Semaphore(permits, true);
        log.info("🚦 提问并发闸门已就绪：全局上限 {}，排队超时 {}ms", permits, queueTimeoutMs);
    }

    @Override
    public Permit acquire(Long userId, String clientIp) {
        String identity = identityOf(userId, clientIp);

        // 身份限制：同一个人同时只能有一个提问。
        // 先占身份再抢名额 —— 反过来会在等名额期间放开身份，同一个人能排好几次队。
        if (identity != null && !inFlight.add(identity)) {
            throw new BizException("你已有一个提问正在进行，等它结束后再问吧");
        }

        boolean acquired = false;
        try {
            acquired = slots.tryAcquire(queueTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // 恢复中断标志再往下走：吞掉中断会让上层的关闭流程失效
            Thread.currentThread().interrupt();
        }

        if (!acquired) {
            // 没抢到名额，身份标记必须撤掉，否则这个人会被永久卡住
            if (identity != null) {
                inFlight.remove(identity);
            }
            log.info("🚦 提问并发已满（上限 {}），排队 {}ms 后仍未拿到名额，拒绝本次请求",
                    maxConcurrent, queueTimeoutMs);
            throw new BizException("当前咨询人数较多，请稍后再试");
        }

        return new PermitImpl(identity);
    }

    /** 已登录按用户 id，匿名退化用 IP；都取不到就只做全局限制。 */
    private static String identityOf(Long userId, String clientIp) {
        if (userId != null) {
            return "u:" + userId;
        }
        if (clientIp != null && !clientIp.isBlank()) {
            return "ip:" + clientIp;
        }
        return null;
    }

    private final class PermitImpl implements Permit {

        private final String identity;
        /** 幂等闸：close 可能被调用多次（doFinally 与兜底的 finally 重合时）。 */
        private final AtomicBoolean released = new AtomicBoolean(false);

        private PermitImpl(String identity) {
            this.identity = identity;
        }

        @Override
        public void close() {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            if (identity != null) {
                inFlight.remove(identity);
            }
            slots.release();
        }
    }
}
