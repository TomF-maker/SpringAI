package com.example.springai.filter;

import com.example.springai.utils.JwtUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private UserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String requestURI = request.getRequestURI();

        // 1. 放行所有非 API 请求（页面、静态资源等）
        if (!requestURI.startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }

        // 2. 放行认证接口
        if (requestURI.startsWith("/api/auth/")) {
            filterChain.doFilter(request, response);
            return;
        }

        // 3. 对 API 请求进行 Token 校验
        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(unauthorized("未提供Token"));
            return;
        }

        String jwtToken = authHeader.substring(7);
        String username = null;

        try {
            username = jwtUtils.getUsernameFromToken(jwtToken);
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(unauthorized("Token无效"));
            return;
        }

        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            UserDetails userDetails = userDetailsService.loadUserByUsername(username);
            if (jwtUtils.validateToken(jwtToken, userDetails.getUsername())) {
                UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authToken);
            } else {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(unauthorized("Token已过期或无效"));
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 过滤器直接写响应体，绕过了 Spring MVC，所以拿不到 @RestControllerAdvice。
     * 这里手工拼成和 {@code Response} 一致的信封形状，保证全站 JSON 结构统一。
     * 状态码保持 401 —— 认证失败是传输层的事实，不能伪装成 200。
     */
    private String unauthorized(String message) {
        return "{\"success\":false,\"errCode\":\"401\",\"errMessage\":\"" + message + "\",\"data\":null}";
    }
}