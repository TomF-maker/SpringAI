package com.example.springai.common;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;

/**
 * 统一返回信封。所有 {@code @RestController} 的返回值都用它包一层。
 *
 * <p><b>关于 {@code isSuccess} 的命名</b>（改之前务必先读这段）：
 * 字段名叫 {@code isSuccess}，但 JSON 里的 key 是 <b>{@code success}</b>。
 * 这个不一致是刻意钉死的 ——
 * <ul>
 *   <li>Jackson 对<b>原始类型</b>的 {@code isXxx()} getter 会剥掉 {@code is} 前缀，
 *       所以线协议本就是 {@code success}；而前端 9 个模板里到处是 {@code data.success} 判断。</li>
 *   <li>但"会剥"的前提是 getter 真的叫 {@code isSuccess()}。如果将来有人用 Lombok
 *       生成出 {@code getIsSuccess()}，JSON 就会变成 {@code isSuccess}，
 *       <b>所有页面的 {@code data.success} 判断会同时静默失效，没有任何报错</b>。</li>
 *   <li>所以这里<b>不用 Lombok</b>，手写访问器并显式标注 {@link JsonProperty}，
 *       把这件事从"依赖推断"变成"写死的"。</li>
 * </ul>
 *
 * <p>顺带记一笔容易看着像 bug 的地方：本项目已有的 {@code isPublic} / {@code isAdmin}
 * 在线上是<b>带 is 前缀</b>的，因为它们都是包装类型（{@code Boolean} / {@code Integer}），
 * Jackson 不剥包装类型的 {@code is}。只有 {@code success} 这个原始 boolean 会被剥。
 */
public class Response<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private boolean isSuccess;
    private String errCode;
    private String errMessage;
    private T data;

    // ==================== 静态工厂 ====================

    /** 成功，无返回数据。 */
    public static <T> Response<T> success() {
        return success(null);
    }

    public static <T> Response<T> success(T data) {
        Response<T> response = new Response<>();
        response.setSuccess(true);
        response.setData(data);
        return response;
    }

    public static <T> Response<T> fail(String errCode, String errMessage) {
        Response<T> response = new Response<>();
        response.setSuccess(false);
        response.setErrCode(errCode);
        response.setErrMessage(errMessage);
        return response;
    }

    public static <T> Response<T> fail(ErrorCodeI errorCodeI) {
        return fail(errorCodeI.getErrCode(), errorCodeI.getErrDesc());
    }

    public static <T> Response<T> fail(ErrorCodeI errorCodeI, String errMessage) {
        return fail(errorCodeI.getErrCode(), errMessage);
    }

    public static <T> Response<T> fail(ErrorCodeI errorCodeI, T data) {
        Response<T> response = fail(errorCodeI.getErrCode(), errorCodeI.getErrDesc());
        response.setData(data);
        return response;
    }

    // ==================== 访问器 ====================

    /** JSON key 固定为 {@code success}，见类注释。 */
    @JsonProperty("success")
    public boolean isSuccess() {
        return isSuccess;
    }

    public void setSuccess(boolean success) {
        this.isSuccess = success;
    }

    public String getErrCode() {
        return errCode;
    }

    public void setErrCode(String errCode) {
        this.errCode = errCode;
    }

    public String getErrMessage() {
        return errMessage;
    }

    public void setErrMessage(String errMessage) {
        this.errMessage = errMessage;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
