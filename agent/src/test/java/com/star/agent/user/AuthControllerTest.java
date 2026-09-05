package com.star.agent.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 认证接口契约测试（mock AuthService，验证端点映射+请求体解析+响应结构）：
// 契约 10-25 冻结——send-code/register/login 三端点，data={token,userId}
@SpringBootTest(properties = "linkagent.wechat.bot-enabled=false")
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    AuthService authService;

    @Autowired
    JwtService jwtService;

    @Test
    void sendCode_mapsBody() throws Exception {
        mvc.perform(post("/api/user/send-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@qq.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(authService).sendCode("a@qq.com");
    }

    @Test
    void register_usernamePath_mapsBody() throws Exception {
        mvc.perform(post("/api/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"username\",\"username\":\"小明\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(authService).register("username", "小明", "123456", null, null);
    }

    @Test
    void login_usernamePath_returnsToken() throws Exception {
        when(authService.login("username", "小明", "123456", null, null))
                .thenReturn(new AuthResult("token-x", "u1"));

        mvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"username\",\"username\":\"小明\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.token").value("token-x"))
                .andExpect(jsonPath("$.data.userId").value("u1"));
    }

    @Test
    void login_emailPath_returnsToken() throws Exception {
        when(authService.login("email", null, null, "a@qq.com", "123456"))
                .thenReturn(new AuthResult("token-y", "u2"));

        mvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"email\",\"email\":\"a@qq.com\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").value("token-y"));
    }

    @Test
    void register_emailPath_returnsAutoLoginToken() throws Exception {
        when(authService.register("email", null, null, "a@qq.com", "123456"))
                .thenReturn(new AuthResult("token-z", "u3"));

        mvc.perform(post("/api/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"email\",\"email\":\"a@qq.com\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").value("token-z"));
    }

    // 累计 token 用量端点：JWT 鉴权后按本人查询返回 totalTokens
    @Test
    void tokens_requiresJwt_returnsTotal() throws Exception {
        when(authService.tokenUsage("u1")).thenReturn(1234L);

        mvc.perform(get("/api/user/tokens")
                        .header("Authorization", "Bearer " + jwtService.issue("u1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.totalTokens").value(1234));

        verify(authService).tokenUsage("u1");
    }

    // 无 token → 401（用户端 JWT 拦截器拒绝）
    @Test
    void tokens_noToken_401() throws Exception {
        mvc.perform(get("/api/user/tokens"))
                .andExpect(status().isUnauthorized());
    }
}