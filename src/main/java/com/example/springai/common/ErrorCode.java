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
    /**
     * 资源冲突：想做的事和当前正在做的事互斥。
     *
     * <p>当前的用处是批量入库 —— 同一时间只允许一个批次执行（否则几批文件会
     * 抢同一份 embedding 资源，把 2 核机器拖垮，进度也没法归属到某一批）。
     * 与 429 的区别：429 是"你太频繁了，等会儿再来"，这个是"系统正忙着一件
     * 已经接受的事，等它做完"。
     */
    CONFLICT("409", "操作冲突，请稍后再试"),
    /**
     * 必须先修改初始密码才能使用平台。
     *
     * <p><b>刻意不复用 403</b>：前端必须能把它和"普通无权限"区分开 ——
     * 前者要跳去改密页，后者只需要提示"没权限"。靠 errMessage 文案判断太脆
     * （改一个字前端就失效）。这是本项目唯一一个不与 HTTP 状态码同值的 errCode，
     * 代价换的是"前端能可靠识别"。
     */
    PASSWORD_CHANGE_REQUIRED("PWD_CHANGE_REQUIRED", "首次登录必须修改初始密码"),
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
