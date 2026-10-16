package com.star.agent.infra.web;

import com.star.agent.user.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 全局异常处理：静态资源不存在必须 404（不是 500 JSON）、业务/降级异常走 Result JSON（HTTP 200 + code）、
// 405/400 调用方问题友好话术不堆栈、响应体带 traceId
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

    // 405：POST 接口用 GET 调（send-code 是 POST）→ 友好话术，不再被兜底成 500（11-10 实测发现）
    @Test
    void wrongMethod_405_not500() throws Exception {
        mvc.perform(get("/api/user/send-code"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(405))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("请求方式不支持")));
    }

    // 400：POST 空 body（@RequestBody 解析失败）→ 友好话术，不是"服务器内部错误"
    @Test
    void emptyBody_400() throws Exception {
        mvc.perform(post("/api/user/send-code"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("请求体")));
    }

    // traceId 入响应体：与响应头 X-Trace-Id 一致（前端报错可凭它查日志）
    @Test
    void errorResponse_carriesTraceId() throws Exception {
        MvcResult result = mvc.perform(get("/api/admin/health"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        String headerTraceId = result.getResponse().getHeader("X-Trace-Id");
        assertNotNull(headerTraceId);
        assertEquals(8, headerTraceId.length());
        String body = result.getResponse().getContentAsString();   // {"code":401,...,"traceId":"abc12345"}
        int idx = body.indexOf("\"traceId\":\"");
        String bodyTraceId = idx >= 0 ? body.substring(idx + 11, idx + 19) : "";
        assertEquals(headerTraceId, bodyTraceId);
    }
}