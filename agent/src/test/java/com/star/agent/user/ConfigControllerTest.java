package com.star.agent.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// ConfigController 端点测试：设置/删除/状态（Web/小程序"我的配置"按钮的后端）
@WebMvcTest(ConfigController.class)
class ConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserConfigRepository repository;

    @Test
    void saveToken_savesAndReturnsConfigured() throws Exception {
        mockMvc.perform(post("/api/config/liepin-token")
                        .param("userId", "u1")
                        .param("token", "eyJhbGciOiJIUzI1NiJ9.abc.def"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(true));

        verify(repository).saveLiepinToken(eq("u1"), eq("eyJhbGciOiJIUzI1NiJ9.abc.def"));
    }

    @Test
    void saveToken_blankToken_returns400() throws Exception {
        mockMvc.perform(post("/api/config/liepin-token")
                        .param("userId", "u1")
                        .param("token", "  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void deleteToken_deletesConfig() throws Exception {
        mockMvc.perform(delete("/api/config/liepin-token").param("userId", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(false));

        verify(repository).deleteLiepinToken(eq("u1"));
    }

    @Test
    void status_returnsConfiguredFlagWithoutToken() throws Exception {
        mockMvc.perform(get("/api/config/liepin-token").param("userId", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(false));
    }
}