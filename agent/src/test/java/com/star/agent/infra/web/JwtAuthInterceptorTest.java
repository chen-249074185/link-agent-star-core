package com.star.agent.infra.web;

import com.star.agent.user.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 用户端 JWT 拦截器集成测试（真实 JwtService + 真实拦截器链，mock 模式不起 MCP）：
// 无 token 401 / 无效 token 401 / 有效放行 / R10 越权 403 / SSE query token 兜底 / 白名单免登录
@SpringBootTest(properties = {
        "linkagent.wechat.bot-enabled=false",
        "linkagent.workflow.mock-enabled=true"
})
@AutoConfigureMockMvc
class JwtAuthInterceptorTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtService jwtService;

    private String token(String userId) {
        return jwtService.issue(userId);
    }

    @Test
    void noToken_401() throws Exception {
        mvc.perform(get("/api/rag/list").param("userId", "u1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void invalidToken_401() throws Exception {
        mvc.perform(get("/api/rag/list")
                        .param("userId", "u1")
                        .header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    // 有效 token + userId 参数一致 → 放行（mock 模式无 MCP 连接，业务层返回 503 code，但 HTTP 200=拦截器已通过）
    @Test
    void validToken_matchingParam_passes() throws Exception {
        mvc.perform(get("/api/rag/list")
                        .param("userId", "u1")
                        .header("Authorization", "Bearer " + token("u1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(503));
    }

    // R10 回归核心：A 的 token 传 B 的 userId → 403 拒
    @Test
    void validToken_otherUserParam_403() throws Exception {
        mvc.perform(get("/api/rag/list")
                        .param("userId", "u2")
                        .header("Authorization", "Bearer " + token("u1")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    // SSE 兜底：EventSource 不能带请求头，token 走 query 参数同样生效
    @Test
    void tokenViaQueryParam_passes() throws Exception {
        mvc.perform(get("/api/rag/list").param("userId", "u1").param("token", token("u1")))
                .andExpect(status().isOk());
    }

    // 白名单：登录接口无 token 直接可用
    @Test
    void whitelist_login_noToken() throws Exception {
        mvc.perform(post("/api/user/login")
                        .contentType("application/json")
                        .content("{\"type\":\"username\",\"username\":\"x\",\"password\":\"123456\"}"))
                .andExpect(status().isOk());
    }

    // 白名单：内部采集工具免登录
    @Test
    void whitelist_collect_noToken() throws Exception {
        mvc.perform(post("/api/collect").param("userId", "u1").param("city", "北京").param("url", "http://x"))
                .andExpect(status().isOk());
    }

    // 管理端路径由 AdminAuthFilter 管，JWT 拦截器不掺和（无 token 应被管理轨 401，而不是用户轨 401 抢先）
    @Test
    void adminPath_notCoveredByJwtInterceptor() throws Exception {
        mvc.perform(get("/api/admin/health"))
                .andExpect(status().isUnauthorized());
    }
}