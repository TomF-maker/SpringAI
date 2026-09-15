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
 *
 * <p>归属地（省/市）也走异步：它要外呼第三方，放在 {@code record} 里会直接抬高
 * 流式问答的首字延迟。IP 本身是字符串，同步写；省市由内部线程池回填。
 */
public interface QuestionLogServiceI {

    /**
     * 记录一次提问。任何异常都会被吞掉并记日志 —— 埋点坏了不能影响业务。
     *
     * @param clientIp 完整客户端 IP（不是风控用的 /24 网段前缀），可为 null。
     *                 <b>必须由调用方在请求线程上取好再传进来</b>：本方法虽在请求线程
     *                 被调用，但归属地回填发生在工作线程上，那里读不到 request。
     * @return 记录 id；写入失败时返回 null
     */
    Long record(String question, Long userId, Long departmentId, String conversationId,
                String hitType, int retrievedCount, String toolName, String clientIp);

    /** 异步补写耗时并置为 COMPLETED。 */
    void markCompleted(Long logId, long elapsedMs);

    /** 异步更新最终状态（如 CANCELLED / ERROR）。 */
    void markStatus(Long logId, String status);
}
