package com.star.agent.travel;

import com.star.agent.user.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 旅游端点鉴权测试（真实 JWT 链 + mock 模式不起 MCP）：
// 未登录 401 / 规划端点带 token 走真实编排（无 key 或目的地错误 → 400 话术不 500）/ map-config 下发 JS key
@SpringBootTest(properties = {
        "linkagent.wechat.bot-enabled=false",
        "linkagent.workflow.mock-enabled=true"
})
@AutoConfigureMockMvc
class TravelControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtService jwtService;

    private String token(String userId) {
        return jwtService.issue(userId);
    }

    // 未登录 → 401（JWT 拦截器）
    @Test
    void noToken_401() throws Exception {
        mvc.perform(get("/api/travel/footprints"))
                .andExpect(status().isUnauthorized());
    }

    // 缺目的地 → 400 话术（不 500）
    @Test
    void plan_missingDestination_400() throws Exception {
        mvc.perform(post("/api/travel/plan")
                        .header("Authorization", "Bearer " + token("u1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("请输入目的地"));
    }

    // 目的地不存在：高德可能模糊匹配成功（code=0）或失败（code=400），核心是 HTTP 200 不 500 不崩
    @Test
    void plan_badDestination_returnsErrorNot500() throws Exception {
        mvc.perform(post("/api/travel/plan")
                        .header("Authorization", "Bearer " + token("u1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"destination\":\"__不存在的地点xyz__\",\"days\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").isNumber());
    }

    // map-config：JWT 放行 + 返回 amapKey 字段（值可能为空但结构在）
    @Test
    void mapConfig_requiresJwt_returnsFields() throws Exception {
        mvc.perform(get("/api/travel/map-config")
                        .header("Authorization", "Bearer " + token("u1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.amapKey").exists());
    }

    // 足迹点：JWT 放行 + 返回数组
    @Test
    void footprints_requiresJwt_returnsArray() throws Exception {
        mvc.perform(get("/api/travel/footprints")
                        .header("Authorization", "Bearer " + token("u1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray());
    }
}