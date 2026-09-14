package com.example.springai.controller;

import com.example.springai.common.ErrorCode;
import com.example.springai.common.Response;
import com.example.springai.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 全局异常处理。
 *
 * <p><b>状态码约定</b>：
 * <ul>
 *   <li>业务/校验失败（{@link BizException}）→ <b>HTTP 200</b> + {@code success:false}</li>
 *   <li>认证失败（{@link BadCredentialsException}）→ <b>401</b></li>
 *   <li>鉴权失败（{@link AccessDeniedException}）→ <b>403</b></li>
 *   <li>服务端故障（其他 {@code RuntimeException}）→ <b>500</b></li>
 * </ul>
 * 信封形状到处一致，状态码如实。
 *
 * <p><b>为什么 {@link AccessDeniedException} 必须在这里处理，而不是配 AccessDeniedHandler</b>：
 * {@code @PreAuthorize} 的拒绝抛的是 {@code AuthorizationDeniedException}，
 * 它继承自 {@code AccessDeniedException}，而后者又继承自 {@code RuntimeException}。
 * 这个异常在 handler 调用期间抛出，由 {@code DispatcherServlet} 的异常解析器接住，
 * <b>根本走不到 {@code ExceptionTranslationFilter}</b> —— 所以给 SecurityConfig 配
 * {@code accessDeniedHandler} 对 {@code @PreAuthorize} 是死代码。
 * 在本类里继承 {@link ResponseEntityExceptionHandler} 后，本类没覆盖的 Spring MVC
 * 异常（畸形 JSON、缺参数、类型不匹配、405 等）会被基类接住，
 * 再由 {@link #handleExceptionInternal} 统一包成信封 —— 这些以前是直接落到
 * whitelabel 的 {@code /error} 上的。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /** 业务异常：可预期的规则拒绝，HTTP 200 + 信封。 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Response<Void>> handleBiz(BizException e) {
        log.info("业务异常: {}", e.getMessage());
        return ResponseEntity.ok(
                Response.fail(e.getErrorCode().getErrCode(), e.getMessage()));
    }

    /** 鉴权失败：@PreAuthorize 拒绝。此前因为没有这个处理器，一直是被下面的兜底当成 500。 */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Response<Void>> handleAccessDenied(AccessDeniedException e) {
        log.warn("鉴权失败: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Response.fail(ErrorCode.FORBIDDEN));
    }

    /** 上传体积超限（默认 50MB），文档页很容易触发；以前是 whitelabel 页面。 */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Response<Void>> handleMultipart(MultipartException e) {
        log.warn("上传请求处理失败: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Response.fail("413", "上传文件过大或请求格式不正确（单文件上限 50MB）"));
    }

    /**
     * 密码错误。<b>保持 HTTP 401</b>，不改成 200。
     *
     * <p>原因：{@code login.html} 的 {@code saveAndGo()} 会无条件写 token 再跳转。
     * 如果密码错误返回 200，前端会把它当成登录成功，把字符串 {@code "undefined"}
     * 存成 token 并跳到 /chat —— <b>登录失败会表现成登录成功</b>。
     * 这里只把 body 换成统一信封，状态码如实。
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Response<Void>> handleBadCredentials() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Response.fail(ErrorCode.UNAUTHORIZED, "用户名或密码错误"));
    }

    /**
     * 兜底：未预期的运行时异常，<b>保持 HTTP 500</b>。
     *
     * <p>{@code e.getMessage()} 必须原样透传 —— 本项目大量用裸
     * {@code RuntimeException} 的消息当 UI 文案（"验证已过期，请重新登录"、
     * "该手机号已被注册" 等），换成罐头文案会悄悄劣化这些提示。
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Response<Void>> handleRuntime(RuntimeException e) {
        log.error("未预期的运行时异常: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Response.fail(ErrorCode.INTERNAL_ERROR, e.getMessage()));
    }

    // ==================== Spring MVC 标准异常的兜底包装 ====================

    /**
     * 把 {@link ResponseEntityExceptionHandler} 处理的所有 Spring MVC 异常
     * 统一包成 {@link Response} 信封，避免它们落到 whitelabel {@code /error} 上。
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
                                                             HttpHeaders headers,
                                                             HttpStatusCode statusCode,
                                                             WebRequest request) {
        String message = ex.getMessage();
        if (body instanceof ProblemDetail detail && detail.getDetail() != null) {
            message = detail.getDetail();
        }
        log.warn("请求处理失败 [{}]: {}", statusCode.value(), message);
        Response<Object> payload = Response.fail(String.valueOf(statusCode.value()), message);
        return new ResponseEntity<>(payload, headers, statusCode);
    }
}
