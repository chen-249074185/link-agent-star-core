package com.star.agent.infra.web;

import com.star.agent.infra.config.AppConfig;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

// 管理端鉴权过滤器（鉴权双轨之管理轨，W12 10-27）：
//   /api/admin/** 必须带 X-Admin-Token 且与 .env 的 ADMIN_TOKEN 一致（恒定时间比较防时序攻击），否则 401
//   与用户端 JWT 完全隔离：管理端无用户概念，超管视角看全库
public class AdminAuthFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(AdminAuthFilter.class);

    private final ObjectProvider<AppConfig> configProvider;

    public AdminAuthFilter(ObjectProvider<AppConfig> configProvider) {
        this.configProvider = configProvider;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        AppConfig config = configProvider.getIfAvailable();
        if (config == null) {
            chain.doFilter(request, response);   // 测试切片无配置 → 不拦
            return;
        }
        String adminToken = config.adminToken();
        if (adminToken.isBlank()) {
            logger.error("ADMIN_TOKEN 未配置，管理端全部拒绝访问（.env 添加后重启）");
            AuthContext.writeError(response, 401, "管理端未配置");
            return;
        }
        String header = request.getHeader("X-Admin-Token");
        boolean ok = header != null && MessageDigest.isEqual(
                adminToken.getBytes(StandardCharsets.UTF_8), header.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            AuthContext.writeError(response, 401, "管理端凭证无效");
            return;
        }
        chain.doFilter(request, response);
    }
}