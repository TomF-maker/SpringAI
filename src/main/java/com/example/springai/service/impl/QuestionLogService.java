package com.example.springai.service.impl;

import com.example.springai.entity.KbQuestionLog;
import com.example.springai.mapper.KbQuestionLogMapper;
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

    private Executor executor;

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
    }

    @Override
    public Long record(String question, Long userId, Long departmentId, String conversationId,
                       String hitType, int retrievedCount, String toolName) {
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
            questionLogMapper.insert(row);
            return row.getId();
        } catch (DataAccessException e) {
            warnTableMissing(e);
            return null;
        } catch (Throwable t) {
            log.warn("提问埋点写入失败: {}", t.getMessage());
            return null;
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
            log.warn("提问埋点表不可用（请先执行 doc/schema.sql 里的 kb_question_log 建表语句），"
                    + "统计功能将不可用。原始错误: {}", e.getMessage());
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
