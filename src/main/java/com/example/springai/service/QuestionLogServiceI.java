package com.example.springai.service;

/**
 * 提问埋点写入。
 *
 * <p>写入分两步，这是刻意的：
 * <ol>
 *   <li>{@link #record} 在**请求线程**上同步插入，此时就知道回答类型。
 *       即使客户端中途断开、流永远不会完成，这条提问也已经落库了。</li>
 *   <li>{@link #markCompleted} 在流结束时**异步**补写耗时。
 *       它跑在 reactor 线程上，绝不能在那里做 JDBC。</li>
 * </ol>
 */
public interface QuestionLogServiceI {

    /**
     * 记录一次提问。任何异常都会被吞掉并记日志 —— 埋点坏了不能影响业务。
     *
     * @return 记录 id；写入失败时返回 null
     */
    Long record(String question, Long userId, Long departmentId, String conversationId,
                String hitType, int retrievedCount, String toolName);

    /** 异步补写耗时并置为 COMPLETED。 */
    void markCompleted(Long logId, long elapsedMs);

    /** 异步更新最终状态（如 CANCELLED / ERROR）。 */
    void markStatus(Long logId, String status);
}
