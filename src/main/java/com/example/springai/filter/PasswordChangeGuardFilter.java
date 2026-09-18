package com.example.springai.filter;

import com.example.springai.common.ErrorCode;
import com.example.springai.service.impl.UserDetailsServiceImpl;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 「必须先改初始密码」的护栏：在改密之前，除了改密本身，其他接口一律拒绝。
 *
 * <h3>为什么必须是服务端拦</h3>
 * 前端跳转到改密页只是引导。真正要防的是"绕过前端直接打接口" ——
 * 关掉弹窗、改 JS、用 curl 带 token 打 {@code /api/rag/chat}，都能跳过前端跳转。
 * 只在页面层做的话，一个拿着初始密码的人照样能把平台当正常账号用。
 *
 * <h3>为什么不做成 {@code @Component}</h3>
 * 加了 {@code @Component}，Spring Boot 会把这个 Filter Bean <b>额外</b>注册成
 * servlet 过滤器，于是它会在两处被执行：安全链里一次、servlet 容器里一次。
 * 而它继承 {@code OncePerRequestFilter}，**第二次会直接跳过**。
 * 谁先跑取决于注册顺序（安全链默认 order=-100，Boot 自动注册是 MAX_VALUE，
 * 恰好安全链在前），但这个顺序是隐式的 —— 一旦哪天调了顺序，本过滤器会在
 * 认证上下文还没建立时先跑一次、什么都不拦就标记"已执行"，
 * 之后那次带着认证的调用被跳过，<b>护栏静默失效</b>。
 * 在 {@link com.example.springai.config.SecurityConfig} 里 new 出来、只挂进安全链，
 * 就不存在这个问题。
 */
public class PasswordChangeGuardFilter extends OncePerRequestFilter {

    /**
     * 「必须改密」状态下仍然允许访问的接口。除此之外的 {@code /api/} 全拒。
     *
     * <p>刻意保持最小：
     * <ul>
     *   <li>{@code /api/users/me/password} —— 改密本身，否则用户被永久卡死</li>
     *   <li>{@code /api/users/me} —— 改密页要拿自己的用户名等信息，纯读</li>
     * </ul>
     *
     * <p><b>不要加</b> {@code /api/rag/chat/quota} 之类"看起来无害"的接口：
     * 额度查询是聊天页的组成部分，放行它就等于允许继续使用平台。
     * {@code /api/auth/**} 不需要列在这里 —— {@code JwtAuthenticationFilter}
     * 对它们直接放行、不建立认证上下文，本过滤器看不到认证就会自然通过。
     */
    private static final List<String> ALLOWED_PATHS = List.of(
            "/api/users/me/password",
            "/api/users/me"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // 页面与静态资源放行：否则用户连改密页都打不开。
        // 页面本身不含数据，真正的数据都在 /api/ 下，拦在那里就够。
        if (!request.getRequestURI().startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }

        if (isPasswordChangeRequired() && !ALLOWED_PATHS.contains(request.getRequestURI())) {
            writeRejected(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    /** 当前请求的登录者是否带着"必须先改密"的标记。 */
    private boolean isPasswordChangeRequired() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getAuthorities() == null) {
            return false;
        }
        for (GrantedAuthority authority : auth.getAuthorities()) {
            if (UserDetailsServiceImpl.PWD_CHANGE_REQUIRED_AUTHORITY.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 过滤器直接写响应体，绕过了 Spring MVC，拿不到 {@code @RestControllerAdvice}，
     * 所以手工拼成和 {@code Response} 一致的信封（同 {@code JwtAuthenticationEntryPoint}）。
     *
     * <p>状态码用 403 而不是 200：这不是"业务拒绝"，是"你这个身份现在不被允许调用
     * 任何接口"。但 {@code errCode} 刻意与 403 不同（见 {@code ErrorCode} 的说明），
     * 前端要靠它区分"要改密"和"普通没权限"。
     */
    private void writeRejected(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"success\":false,\"errCode\":\"" + ErrorCode.PASSWORD_CHANGE_REQUIRED.getErrCode()
                        + "\",\"errMessage\":\"" + ErrorCode.PASSWORD_CHANGE_REQUIRED.getErrDesc()
                        + "\",\"data\":null}");
    }
}
