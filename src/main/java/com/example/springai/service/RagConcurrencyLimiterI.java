package com.example.springai.service;

/**
 * 提问并发闸门。
 *
 * <p><b>为什么需要它</b>：生产服务器只有 <b>2 核</b>，而 Ollama 在这台机器上
 * 单次生成就基本吃满全部核心。不设上限的话，几个人同时提问会让每个请求都变得极慢，
 * 而且内存（7.6G、无 swap）会随并发线性上涨 —— 这是"把服务器搞宕机"的实际路径。
 *
 * <p>两道限制叠加：
 * <ol>
 *   <li><b>全局并发上限</b>（{@code app.rag.concurrency.max-concurrent}，默认 2）——
 *       保护服务器；</li>
 *   <li><b>同一身份同时只能有一个提问</b> —— 否则一个人开几个标签页就能把名额占满，
 *       其他人一律被拒。</li>
 * </ol>
 * 身份取用户 id；匿名用户没有 id，退化用客户端 IP（与匿名配额同一套判据）。
 * 两者都取不到时只做全局限制，不做身份限制。
 */
public interface RagConcurrencyLimiterI {

    /**
     * 占用一个名额。<b>用完必须 {@link Permit#close()}。</b>
     *
     * @param userId   已登录用户 id；匿名为 null
     * @param clientIp 完整客户端 IP；取不到为 null
     * @return 名额凭据，close 幂等
     * @throws com.example.springai.exception.BizException
     *         该身份已有进行中的提问，或排队超时还拿不到名额时。
     *         <b>必须抛 {@code BizException}</b> —— 流式接口的 catch 是
     *         {@code catch (BizException e)}，抛 RuntimeException 会逃出返回 Flux 的方法、
     *         变成 JSON 污染 SSE。
     */
    Permit acquire(Long userId, String clientIp);

    /** 名额凭据。{@code close()} 幂等 —— 重复释放会让信号量超出上限，那等于闸门失效。 */
    interface Permit extends AutoCloseable {
        @Override
        void close();
    }
}
