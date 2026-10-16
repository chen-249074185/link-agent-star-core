package com.star.agent.infra.web;

import com.star.agent.user.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

// 用户端 JWT 拦截器（鉴权双轨之用户轨）：
//   /api/**（除登录注册白名单 + 管理端 /api/admin/**）必须先带有效 JWT——
//   ① 无/无效 token → 401；② token 的 userId 与请求参数 userId 不一致 → 403（R10：A 不能碰 B 的数据）
// token 来源：Authorization: Bearer <token> 优先，query 参数 token 兜底（SSE EventSource 不能自定义请求头）
// 身份以 token 为准（AuthContext.userId()），请求参数只是冗余展示，不一致即拒
@Component
public class JwtAuthInterceptor implements HandlerInterceptor {

    private final ObjectProvider<JwtService> jwtServiceProvider;

    public JwtAuthInterceptor(ObjectProvider<JwtService> jwtServiceProvider) {
        this.jwtServiceProvider = jwtServiceProvider;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        JwtService jwtService = jwtServiceProvider.getIfAvailable();
        if (jwtService == null) {
            // 测试切片（@WebMvcTest）与手动链路没有 JWT 服务 → 不拦截；生产必有（JWT_SECRET 缺失启动即抛）
            return true;
        }
        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        if (token == null || token.isBlank()) {
            token = request.getParameter("token");   // SSE(EventSource) 不能带请求头，query 兜底
        }
        String userId = jwtService.parseUserId(token);
        if (userId == null) {
            AuthContext.writeError(response, 401, "未登录或登录已过期");
            return false;
        }
        request.setAttribute(AuthContext.ATTR_USER_ID, userId);
        String paramUserId = request.getParameter("userId");
        if (paramUserId != null && !paramUserId.isBlank() && !paramUserId.equals(userId)) {
            AuthContext.writeError(response, 403, "无权访问他人数据");
            return false;
        }
        return true;
    }
}