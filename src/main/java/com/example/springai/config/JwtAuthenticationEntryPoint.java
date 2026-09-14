package com.example.springai.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        // 返回 401 Unauthorized
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        // 与 GlobalExceptionHandler / JwtAuthenticationFilter 保持同一信封形状；
        // 状态码仍是 401，不伪装成 200
        response.getWriter().write(
                "{\"success\":false,\"errCode\":\"401\",\"errMessage\":\"未授权，请先登录\",\"data\":null}");
    }
}