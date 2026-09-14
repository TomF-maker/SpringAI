package com.example.springai.common;

/**
 * 通用错误码。
 *
 * <p>errCode 与 HTTP 状态码数值一致，便于排查；但两者是独立的 ——
 * 业务失败按约定返回 HTTP 200，只有认证/服务端故障才真的用 401/403/500。
 */
public enum ErrorCode implements ErrorCodeI {

    UNAUTHORIZED("401", "未授权，请先登录"),
    FORBIDDEN("403", "没有权限执行该操作"),
    BAD_REQUEST("400", "请求参数错误"),
    NOT_FOUND("404", "资源不存在"),
    /** 超出配额（如匿名单日提问次数）。按业务失败处理，HTTP 仍是 200。 */
    TOO_MANY_REQUESTS("429", "请求过于频繁"),
    INTERNAL_ERROR("500", "服务器内部错误");

    private final String errCode;
    private final String errDesc;

    ErrorCode(String errCode, String errDesc) {
        this.errCode = errCode;
        this.errDesc = errDesc;
    }

    @Override
    public String getErrCode() {
        return errCode;
    }

    @Override
    public String getErrDesc() {
        return errDesc;
    }
}
