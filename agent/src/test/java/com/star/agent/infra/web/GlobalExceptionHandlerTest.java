package com.star.agent.infra.web;

import com.star.agent.user.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 全局异常处理：静态资源不存在必须 404（不是 500 JSON）、业务/降级异常走 Result JSON（HTTP 200 + code）
@SpringBootTest(properties = {
        "linkagent.wechat.bot-enabled=false",
        "linkagent.workflow.mock-enabled=true"
})
@AutoConfigureMockMvc
class GlobalExceptionHandlerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtService jwtService;

    @Test
    void missingStaticResource_404_not500() throws Exception {
        mvc.perform(get("/no-such-file.html"))
                .andExpect(status().isNotFound());
    }

    // 带 token 走业务端点：mock 模式无 MCP 连接 → 降级 Result（HTTP 200 + code=503），验证异常不崩、格式统一
    @Test
    void bizException_resultJson() throws Exception {
        mvc.perform(get("/api/rag/list")
                        .param("userId", "u1")
                        .header("Authorization", "Bearer " + jwtService.issue("u1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").exists());
    }
}