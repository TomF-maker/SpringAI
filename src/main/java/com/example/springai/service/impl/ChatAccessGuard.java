package com.example.springai.service.impl;

import com.example.springai.common.AppTime;
import com.example.springai.common.ErrorCode;
import com.example.springai.entity.SysUser;
import com.example.springai.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 提问准入：三档阶梯。
 *
 * <pre>
 *   匿名            → 按 IP 每日限额（AnonymousQuestionLimiter）
 *   有效会员        → 放行，不计次
 *   已登录非会员    → 每日免费额度（FreeQuestionQuotaLimiter）
 * </pre>
 *
 * <p>收在一个方法里是因为 {@code /chat} 和 {@code /chat/stream} 两个调用点必须一致 ——
 * 分开写迟早会变成"只改了流式那边"。
 *
 * <p><b>拒绝时一律抛 {@link BizException}。</b>这不是风格问题：{@code chatStream} 的
 * catch 是 {@code catch (BizException e)}，抛 {@code RuntimeException} 抓不住，
 * 异常会逃出一个返回 {@code Flux} 的方法，被 {@code @RestControllerAdvice} 变成
 * JSON 错误体 —— SSE 客户端解析直接乱掉。
 */
@Slf4j
@Component
public class ChatAccessGuard {

    @Autowired
    private AnonymousQuestionLimiter anonymousQuestionLimiter;

    @Autowired
    private FreeQuestionQuotaLimiter freeQuestionQuotaLimiter;

    /**
     * 准入判定 + 计次。超限/不允许时抛 {@link BizException}。
     *
     * <p><b>必须在调用 service 之前、且在 try 之外调用</b> ——
     * 放进 try 里会被 {@code catch (Exception)} 吞成"处理失败"，
     * 并且往 {@code kb_question_log} 写一条假的 HIT_ERROR（看板上凭空多出检索失败）。
     *
     * @param anonymous 是否未登录
     * @param user      当前用户；匿名时传 null
     * @param clientIp  归一化后的客户端 IP；匿名时用于计次
     */
    public void checkAndRecord(boolean anonymous, SysUser user, String clientIp) {
        if (anonymous) {
            anonymousQuestionLimiter.checkAndRecord(clientIp);
            return;
        }
        if (user == null) {
            // 用户被删除但 token 未过期。从严拒绝，不要当成匿名放过去。
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户不存在，请重新登录");
        }

        // 每请求只判定一次会员状态，往下传同一个值。
        // 判定为会员后请求中途到期这个窗口关不掉（除非把行锁跨越整段 LLM 调用，
        // 那是绝对禁止的），接受不追溯。
        if (user.hasActiveMembership(AppTime.now())) {
            return;
        }
        freeQuestionQuotaLimiter.checkAndRecord(user.getId());
    }
}
