package com.star.agent.infra.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

// 请求级 traceId：每个 HTTP 请求一个 traceId 贯穿全链路日志
//   - 请求头 X-Trace-Id 有值则沿用（排查问题时前端可带 id 关联后端日志），否则生成 UUID 前 8 位
//   - 写入 MDC → logback 的 [%X{traceId}] 自动打出（与微信链路 MessageDispatcher 同格式同命名）
//   - 响应头回传 X-Trace-Id 供调用方取用；finally 清理防线程池复用串号
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)   // 最先执行：鉴权失败/静态资源请求也要有 traceId
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = request.getHeader(HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().substring(0, 8);
        }
        MDC.put("traceId", traceId);
        try {
            response.setHeader(HEADER, traceId);
            chain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
        }
    }
}