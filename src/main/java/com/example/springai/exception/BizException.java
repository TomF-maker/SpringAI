package com.example.springai.exception;

import com.example.springai.common.ErrorCode;
import com.example.springai.common.ErrorCodeI;

/**
 * 业务异常：可预期的、由业务规则拒绝的失败。
 *
 * <p>与 {@code RuntimeException} 的区别在于语义：
 * <ul>
 *   <li>{@code BizException} → 业务/校验失败，按约定返回 <b>HTTP 200</b> + {@code success:false}，
 *       errCode 用这里的值。</li>
 *   <li>裸 {@code RuntimeException} → 现状保留，兜底映射成 {@code INTERNAL_ERROR} + HTTP 500。</li>
 * </ul>
 *
 * <p>现有代码里的 {@code throw new RuntimeException("...")} <b>不需要</b>逐个改写成这个 ——
 * 那些消息本身就是给用户看的文案，兜底处理器会原样透传。
 * 只有当某个接口需要精确的 errCode 时，才换成这个。
 */
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCodeI errorCode;

    public BizException(ErrorCodeI errorCode) {
        super(errorCode.getErrDesc());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCodeI errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BizException(ErrorCodeI errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public BizException(String message) {
        this(ErrorCode.BAD_REQUEST, message);
    }

    public ErrorCodeI getErrorCode() {
        return errorCode;
    }
}
