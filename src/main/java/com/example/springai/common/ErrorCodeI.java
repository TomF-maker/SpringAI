package com.example.springai.common;

/**
 * 错误码契约。
 *
 * <p>配合 {@link Response#fail(ErrorCodeI)} 系列重载使用，让调用方不必手写错误码字符串。
 */
public interface ErrorCodeI {

    /** 错误码，用字符串形式的数字，方便以后接网关/国际化。 */
    String getErrCode();

    /** 错误描述（默认文案）。 */
    String getErrDesc();
}
