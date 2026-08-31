package com.star.agent.infra.web;

import com.star.agent.infra.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 管理端鉴权过滤器集成测试（真实 ADMIN_TOKEN_WEB，从 .env 读）：
// 无头 401 / 错误头 401 / 正确头放行 / 非管理路径不拦截
@SpringBootTest(properties = {
        "linkagent.wechat.bot-enabled=false",
        "linkagent.workflow.mock-enabled=true"
})
@AutoConfigureMockMvc
class AdminAuthFilterTest {

    @Autowired
    MockMvc mvc;

    @Test
    void noToken_401() throws Exception {
        mvc.perform(get("/api/admin/health"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void wrongToken_401() throws Exception {
        mvc.perform(get("/api/admin/health").header("X-Admin-Token", "wrong-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void correctToken_passes() throws Exception {
        String adminToken = new AppConfig().adminToken();   // 真实 .env 的 ADMIN_TOKEN_WEB（测试 CWD=agent/agent 能读到）

        mvc.perform(get("/api/admin/health").header("X-Admin-Token", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("ok"));
    }

    // 过滤器只拦 /api/admin/*，用户路径不受影响（登录白名单照常）
    @Test
    void nonAdminPath_notFiltered() throws Exception {
        mvc.perform(post("/api/user/login")
                        .contentType("application/json")
                        .content("{\"type\":\"username\",\"username\":\"x\",\"password\":\"123456\"}"))
                .andExpect(status().isOk());
    }
}