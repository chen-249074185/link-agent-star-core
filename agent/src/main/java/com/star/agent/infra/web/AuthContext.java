package com.star.agent.infra.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;

// 请求身份上下文：拦截器把 JWT 解析出的 userId 放请求 attribute，控制器用 userId() 取（不信任请求参数）
public final class AuthContext {

    public static final String ATTR_USER_ID = "auth.userId";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AuthContext() {
    }

    // 当前请求身份（拦截器放进去的；未经过拦截器 → null）
    public static String userId(HttpServletRequest request) {
        Object v = request.getAttribute(ATTR_USER_ID);
        return v instanceof String s ? s : null;
    }

    // 统一鉴权失败响应：HTTP 状态码 + Result JSON（前端按 status 跳登录，按 body.msg 展示）
    public static void writeError(HttpServletResponse response, int status, String msg) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(MAPPER.writeValueAsString(Result.fail(status, msg)));
    }
}