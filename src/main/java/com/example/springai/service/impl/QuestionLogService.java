package com.example.springai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.springai.common.IpGeo;
import com.example.springai.entity.KbQuestionLog;
import com.example.springai.mapper.KbQuestionLogMapper;
import com.example.springai.service.IpGeoServiceI;
import com.example.springai.service.QuestionLogServiceI;
import com.example.springai.utils.QuestionNormalizer;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class QuestionLogService implements QuestionLogServiceI {

    @Autowired
    private KbQuestionLogMapper questionLogMapper;

    @Autowired
    private IpGeoServiceI ipGeoService;

    private Executor executor;

    /** 归属地补写专用池。**必须和 {@link #executor} 分开**，理由见 {@link #init()}。 */
    private Executor geoExecutor;

    /** 表不存在时只提示一次，避免刷屏。 */
    private final AtomicBoolean tableMissingWarned = new AtomicBoolean(false);

    @PostConstruct
    public void init() {
        // 专用于补写耗时的小线程池。核心/最大线程数都很小、队列有限：
        // 这是可有可无的统计更新，宁可丢弃也不能反压到流式响应上。
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(2);
        taskExecutor.setMaxPoolSize(2);
        taskExecutor.setQueueCapacity(200);
        taskExecutor.setThreadNamePrefix("qlog-");
        // 默认就是 AbortPolicy：满了直接抛 RejectedExecutionException，
        // 由下面的 catch 丢弃 —— 绝不能用 CallerRunsPolicy，
        // 那等于在 reactor 线程上跑 JDBC，正是要避免的事。
        taskExecutor.initialize();
        this.executor = taskExecutor;

        // 归属地补写池。**刻意与上面的 qlog- 池分开**：
        // 外呼一次最长 800ms，若和 markCompleted / markStatus 抢同样 2 个线程和 200 的队列，
        // 一次服务商抽风就能把队列填满 → 状态更新被丢弃 → latency_ms 停在 NULL →
        // 被 KbQuestionLogMapper.selectLatencyStats 的 "latency_ms IS NOT NULL" 过滤掉，
        // 结果是"看板上的平均耗时只统计了活下来的那部分"，系统性偏低且完全看不出来。
        //
        // 单线程是刻意的：它顺带把"同一个新 IP 被并发击穿"变成串行 ——
        // 第一个请求填缓存，后面的直接命中，不需要额外的单飞锁。
        ThreadPoolTaskExecutor geoPool = new ThreadPoolTaskExecutor();
        geoPool.setCorePoolSize(1);
        geoPool.setMaxPoolSize(1);
        geoPool.setQueueCapacity(1000);
        geoPool.setThreadNamePrefix("geo-");
        // 同样用默认的 AbortPolicy：绝不能用 CallerRunsPolicy，
        // 那等于在请求线程或 reactor 线程上跑 HTTP 外呼。
        geoPool.initialize();
        this.geoExecutor = geoPool;

        verifyGeoColumns();
    }

    /**
     * 启动探针：确认 {@code kb_question_log} 的四个归属地列存在。
     *
     * <p>为什么需要：加列的 DDL 是【阻塞项】，先执行 DDL 再部署 jar。忘了的话
     * INSERT 会因为列清单里有不存在的列而失败，而 {@link #record} 的
     * {@code catch (Throwable)} 会把异常吞掉 —— 应用照常启动、照样回答问题，
     * 只是**所有提问埋点静默消失**，并且 {@link #warnTableMissing} 打的是
     * "提问埋点表不可用"，把排查方向带偏（表好好的，是**列**缺）。
     */
    private void verifyGeoColumns() {
        try {
            questionLogMapper.selectList(
                    new QueryWrapper<KbQuestionLog>()
                            .select("id", "ip_address", "ip_country", "ip_province", "ip_city")
                            .last("LIMIT 1"));
            log.info("📍 提问埋点归属地列就绪");
        } catch (Exception e) {
            log.error("❌ kb_question_log 的归属地列不可用，本次提问埋点将不带 IP 归属地"
                    + "（且可能整条埋点写入失败）。请先执行 doc/schema.sql 里的 2026-09-15 段 ALTER: {}",
                    e.getMessage());
        }
    }

    @Override
    public Long record(String question, Long userId, Long departmentId, String conversationId,
                       String hitType, int retrievedCount, String toolName, String clientIp) {
        try {
            KbQuestionLog row = new KbQuestionLog();
            row.setUserId(userId);
            row.setConversationId(conversationId);
            row.setQuestion(truncate(question, 1000));
            row.setQuestionNorm(QuestionNormalizer.normalize(question));
            row.setHitType(hitType);
            row.setRetrievedCount(retrievedCount);
            row.setToolName(toolName);
            row.setStatus(KbQuestionLog.STATUS_COMPLETED);
            row.setSource("LIVE");
            // 完整 IP 只是个字符串，任务里就能拿到，放在请求线程上写零成本。
            // 归属地（省/市）才是要外呼的那部分，见 resolveGeoAsync。
            row.setIpAddress(clientIp);
            questionLogMapper.insert(row);
            resolveGeoAsync(row.getId(), clientIp);
            return row.getId();
        } catch (DataAccessException e) {
            warnTableMissing(e);
            return null;
        } catch (Throwable t) {
            log.warn("提问埋点写入失败: {}", t.getMessage());
            return null;
        }
    }

    /**
     * 把归属地解析与回填丢给专用池。**只入队，不做任何 IO** —— 这个方法是在请求线程上被调用的，
     * 而它在流式问答里位于 {@code return Flux} 之前，任何阻塞都会直接抬高首字延迟。
     *
     * <p>复用本类已有的"请求线程同步 INSERT + 异步 UPDATE"形状（同 {@link #markCompleted}），
     * 不引入新概念。
     *
     * <p>解析结果是空时直接跳过 UPDATE：没必要为一次"查不到"多写一次库，
     * 那几列留 NULL 就是"未解析"的正确表达。
     */
    private void resolveGeoAsync(Long logId, String clientIp) {
        if (logId == null) {
            return;
        }
        try {
            geoExecutor.execute(() -> {
                try {
                    IpGeo geo = ipGeoService.lookup(clientIp);
                    if (geo == IpGeo.UNKNOWN) {
                        return;
                    }
                    KbQuestionLog update = new KbQuestionLog();
                    update.setId(logId);
                    update.setIpCountry(geo.getCountry());
                    update.setIpProvince(geo.getProvince());
                    update.setIpCity(geo.getCity());
                    questionLogMapper.updateById(update);
                } catch (Throwable t) {
                    // 归属地是锦上添花，失败绝不能冒泡 —— 尤其不能变成聊天故障
                    log.debug("归属地补写失败 logId={}: {}", logId, t.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("归属地补写队列已满，丢弃本次 logId={}", logId);
        } catch (Throwable t) {
            log.debug("归属地补写任务提交失败: {}", t.getMessage());
        }
    }

    @Override
    public void markCompleted(Long logId, long elapsedMs) {
        if (logId == null) {
            return;
        }
        submit(() -> {
            KbQuestionLog update = new KbQuestionLog();
            update.setId(logId);
            update.setLatencyMs(elapsedMs);
            update.setStatus(KbQuestionLog.STATUS_COMPLETED);
            questionLogMapper.updateById(update);
        }, "补写耗时");
    }

    @Override
    public void markStatus(Long logId, String status) {
        if (logId == null) {
            return;
        }
        submit(() -> {
            KbQuestionLog update = new KbQuestionLog();
            update.setId(logId);
            update.setStatus(status);
            questionLogMapper.updateById(update);
        }, "更新状态");
    }

    /**
     * 把任务丢给专用线程池。
     *
     * <p>三处防护缺一不可：
     * <ul>
     *   <li>队列满时 {@link RejectedExecutionException} → 丢弃并记日志；</li>
     *   <li>任务体内任何异常都要 catch —— 在 {@code doOnComplete} 里抛出
     *       会变成下游 onError，把一次埋点故障变成聊天故障；</li>
     *   <li>线程池独立，避免影响其它任务。</li>
     * </ul>
     */
    private void submit(Runnable task, String what) {
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (DataAccessException e) {
                    warnTableMissing(e);
                } catch (Throwable t) {
                    log.warn("提问埋点{}失败: {}", what, t.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("提问埋点队列已满，丢弃本次{}", what);
        } catch (Throwable t) {
            log.warn("提问埋点任务提交失败: {}", t.getMessage());
        }
    }

    private void warnTableMissing(DataAccessException e) {
        if (tableMissingWarned.compareAndSet(false, true)) {
            // 文案刻意同时提"表"和"列"：加归属地列那次 DDL 是【阻塞项】，
            // 漏执行的话表是好的、只是列缺，只提"建表语句"会把排查方向带偏。
            log.warn("提问埋点写入失败（表或列缺失，请先执行 doc/schema.sql 里 kb_question_log 的"
                    + "建表/加列语句），统计功能将不可用。原始错误: {}", e.getMessage());
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
