package com.example.springai.controller;

import com.example.springai.common.Response;
import com.example.springai.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartException;
import reactor.core.publisher.Flux;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统一信封的对外契约，以及"哪些接口<b>不能</b>被包裹"的守卫。
 *
 * <p>纯单元测试，不启动 Spring、不连数据库。
 */
class EnvelopeContractTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    // ==================== 状态码约定 ====================

    @Test
    void businessFailureIsHttp200WithEnvelope() {
        ResponseEntity<Response<Void>> resp = handler.handleBiz(new BizException("用户名已被占用"));

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "业务失败按约定返回 HTTP 200，错误放在信封里");
        assertNotNull(resp.getBody());
        assertFalse(resp.getBody().isSuccess());
        assertEquals("400", resp.getBody().getErrCode());
        assertEquals("用户名已被占用", resp.getBody().getErrMessage());
    }

    @Test
    void badCredentialsStaysHttp401() {
        ResponseEntity<Response<Void>> resp = handler.handleBadCredentials();

        // 这个断言很关键：如果密码错误变成 200，login.html 的 saveAndGo()
        // 会把字符串 "undefined" 当成 token 存起来并跳转 —— 登录失败会表现成登录成功。
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode(),
                "密码错误必须保持 401，改成 200 会造成静默的认证绕过");
        assertNotNull(resp.getBody());
        assertFalse(resp.getBody().isSuccess());
        assertEquals("401", resp.getBody().getErrCode());
    }

    @Test
    void accessDeniedIsHttp403Not500() {
        ResponseEntity<Response<Void>> resp =
                handler.handleAccessDenied(new AccessDeniedException("denied"));

        // @PreAuthorize 的拒绝此前会被 RuntimeException 兜底成 500，
        // 因为 AuthorizationDeniedException 继承自 RuntimeException。
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("403", resp.getBody().getErrCode());
    }

    @Test
    void unexpectedRuntimeExceptionStaysHttp500AndKeepsMessage() {
        String uiCopy = "验证已过期，请重新登录";
        ResponseEntity<Response<Void>> resp = handler.handleRuntime(new RuntimeException(uiCopy));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        assertNotNull(resp.getBody());
        // 本项目大量用裸 RuntimeException 的消息当 UI 文案，必须原样透传
        assertEquals(uiCopy, resp.getBody().getErrMessage(),
                "兜底处理不能把消息换成罐头文案，否则会劣化这些提示");
    }

    @Test
    void oversizedUploadReturnsPayloadTooLarge() {
        ResponseEntity<Response<Void>> resp =
                handler.handleMultipart(new MultipartException("too big"));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertFalse(resp.getBody().isSuccess());
    }

    // ==================== 绝不能包裹的接口 ====================

    @Test
    void chatStreamMustStayAFlux() throws Exception {
        Method m = RagController.class.getMethod("chatStream", String.class, String.class,
                Authentication.class, HttpServletRequest.class);

        // 包成 Response<Flux<String>> 会让 Spring 不再走 ReactiveTypeHandler，
        // 转由 Jackson 序列化 Flux → InvalidDefinitionException → SSE 直接 500。
        assertEquals(Flux.class, m.getReturnType(),
                "SSE 流式接口的返回类型必须是 Flux，不能包信封");
    }

    @Test
    void documentDownloadMustStayAResourceResponse() throws Exception {
        Method m = DocumentController.class.getMethod("downloadDocument", Long.class,
                Authentication.class);

        assertEquals(ResponseEntity.class, m.getReturnType(),
                "下载接口必须保持 ResponseEntity<Resource>，不能被信封包裹");
        // 泛型实参是 Resource 而不是 Response
        String generic = m.getGenericReturnType().getTypeName();
        assertTrue(generic.contains(Resource.class.getName()),
                "下载接口的泛型应该是 Resource，实际: " + generic);
    }

    @Test
    void pageControllerStaysAViewController() {
        assertTrue(PageController.class.isAnnotationPresent(Controller.class),
                "PageController 应保持 @Controller（返回视图名）");
        assertFalse(PageController.class.isAnnotationPresent(RestController.class),
                "PageController 不能是 @RestController，否则视图名会被当 JSON 序列化");
    }

    @Test
    void restControllersReturnTheEnvelope() throws Exception {
        assertEnvelopeReturnType(RoleController.class, "listAllRoles");
        assertEnvelopeReturnType(DashboardController.class, "getStatistics");
        assertEnvelopeReturnType(DepartmentController.class, "getTree");
        assertEnvelopeReturnType(ConversationController.class, "getUserConversations");
    }

    private void assertEnvelopeReturnType(Class<?> controller, String methodName) {
        Method found = null;
        for (Method candidate : controller.getDeclaredMethods()) {
            if (candidate.getName().equals(methodName)) {
                found = candidate;
                break;
            }
        }
        assertNotNull(found, controller.getSimpleName() + "." + methodName + " 不存在");
        assertEquals(Response.class, found.getReturnType(),
                controller.getSimpleName() + "." + methodName + " 应返回 Response 信封");
    }
}
