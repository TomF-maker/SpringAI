package com.example.springai.config;

import com.example.springai.filter.JwtAuthenticationFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Autowired
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Autowired
    private JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                )
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 放行所有页面和静态资源
                        // 页面路由白名单。**新增页面必须加到这里** ——
                        // JwtAuthenticationFilter 只处理 /api/ 开头的请求，
                        // 页面请求拿不到任何 Authentication，落到
                        // .anyRequest().authenticated() 上就是 403。
                        .requestMatchers("/", "/history", "/login", "/register", "/forgot-password", "/chat", "/documents", "/users", "/departments", "/profile", "/dashboard", "/screen", "/feedback-review", "/css/**", "/js/**", "/favicon.ico", "/error").permitAll()
                        // 放行认证 API（/api/test/** 已随测试接口一起删除）
                        .requestMatchers("/api/auth/**").permitAll()
                        // 放行 RAG 问答：允许未登录用户提问（按 IP 每日限额在
                        // AnonymousQuestionLimiter 里控制，不在这里）。
                        // /api/rag/chat 这个前缀同时覆盖 /chat 和 /chat/stream。
                        .requestMatchers("/api/rag/chat/**", "/api/rag/chat").permitAll()
                        // 放行流接口
                        .requestMatchers("/api/qdrant/clear").permitAll()
                        // 其他所有 API 需要认证
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}