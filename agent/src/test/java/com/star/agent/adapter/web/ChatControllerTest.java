package com.star.agent.adapter.web;

import com.star.agent.memory.ChatHistoryService;
import com.star.agent.scene.Scene;
import com.star.agent.scene.SceneDetector;
import com.star.agent.service.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
// ChatController 端点测试：/stream 发 streamId 事件；/stop 终止并移除；历史列表/详情/删除/搜索
// @WebMvcTest 只加载 Web 层，SseStreamRegistry(@Component) 要 @Import 显式带进来
@WebMvcTest(ChatController.class)
@Import(SseStreamRegistry.class)
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SseStreamRegistry registry;

    @MockBean
    private ChatService chatService;

    @MockBean
    private SceneDetector sceneDetector;

    @MockBean
    private ChatHistoryService chatHistoryService;

    @Test
    void stream_returnsSseWithStreamIdEvent() throws Exception {
        when(sceneDetector.detect(anyString())).thenReturn(Scene.CHAT);
        // streamWithTools() 什么都不推，直接回调结束（模拟空流）；切片无拦截器 → userId=null
        doAnswer(inv -> null).when(chatService).streamWithTools(anyString(), any(), any(), anyString(), any(), any());

        mockMvc.perform(get("/api/chat/stream").param("message", "你好"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/event-stream"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:streamId")));
    }

    // 非流式对话（W13 小程序降级用）：JWT 身份在 request attribute 时正常返回回复；未登录 401
    @Test
    void ask_returnsFullReply() throws Exception {
        when(sceneDetector.detect(anyString())).thenReturn(Scene.CHAT);
        when(chatService.resolveSessionId(eq("u1"), any())).thenReturn("s-abc");
        when(chatService.chat(eq("你好"), eq("u1"), eq("CHAT"))).thenReturn("完整回复内容");

        mockMvc.perform(get("/api/chat/ask")
                        .param("message", "你好")
                        .requestAttr(com.star.agent.infra.web.AuthContext.ATTR_USER_ID, "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.reply").value("完整回复内容"))
                .andExpect(jsonPath("$.data.sessionId").value("s-abc"));
    }

    @Test
    void ask_notLoggedIn_returns401() throws Exception {
        mockMvc.perform(get("/api/chat/ask").param("message", "你好"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
    }

    // 工具执行前推 tool 命名事件（前端展示"正在调用xxx"）；正文照常走 data 事件
    @Test
    void stream_emitsToolEventBeforeTokens() throws Exception {
        when(sceneDetector.detect(anyString())).thenReturn(Scene.DATA);
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> onToolStatus =
                    (java.util.function.Consumer<String>) inv.getArgument(5);
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> onToken =
                    (java.util.function.Consumer<String>) inv.getArgument(4);
            onToolStatus.accept("get_weather");
            onToken.accept("北京今天晴，25℃");
            return null;
        }).when(chatService).streamWithTools(anyString(), any(), any(), anyString(), any(), any());

        mockMvc.perform(get("/api/chat/stream").param("message", "北京天气"))
                .andExpect(status().isOk())
                // SSE 响应头无 charset，MockMvc 按 ISO-8859-1 解码中文会乱码 → 用字节按 UTF-8 解码后断言
                .andExpect(result -> {
                    String body = new String(result.getResponse().getContentAsByteArray(),
                            java.nio.charset.StandardCharsets.UTF_8);
                    org.hamcrest.MatcherAssert.assertThat(body,
                            org.hamcrest.Matchers.allOf(
                                    org.hamcrest.Matchers.containsString("event:tool"),
                                    org.hamcrest.Matchers.containsString("get_weather"),
                                    org.hamcrest.Matchers.containsString("北京今天晴，25℃")));
                });
    }

    @Test
    void stop_unknownStream_returnsNotFound() throws Exception {
        mockMvc.perform(get("/api/chat/stop").param("streamId", "ghost"))
                .andExpect(status().isOk())
                .andExpect(content().string("流不存在或已结束"));
    }

    @Test
    void stop_registeredStream_terminates() throws Exception {
        // 手动登记一条流
        String id = "itest123";
        registry.register(id, new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(),
                new java.util.concurrent.atomic.AtomicBoolean(false));
        assertTrue(registry.contains(id));

        mockMvc.perform(get("/api/chat/stop").param("streamId", id))
                .andExpect(status().isOk())
                .andExpect(content().string("已停止"));

        assertFalse(registry.contains(id));   // 停止即移除
    }

    @Test
    void history_returnsSessions() throws Exception {
        when(chatHistoryService.list("u1")).thenReturn(List.of(
                Map.of("convId", "1-2", "title", "北京租房", "time", "2026-10-28T09:00:00", "count", 2)));

        mockMvc.perform(get("/api/chat/history").param("userId", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].title").value("北京租房"))
                .andExpect(jsonPath("$.data[0].convId").value("1-2"));
    }

    @Test
    void historyDetail_returnsMessages() throws Exception {
        when(chatHistoryService.detail("u1", "1-2")).thenReturn(List.of(
                Map.of("role", "user", "content", "问", "time", "t1")));

        mockMvc.perform(get("/api/chat/history/1-2").param("userId", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].role").value("user"))
                .andExpect(jsonPath("$.data[0].content").value("问"));
    }

    @Test
    void deleteHistory_removesSession() throws Exception {
        when(chatHistoryService.delete("u1", "1-2")).thenReturn(true);
        mockMvc.perform(delete("/api/chat/history/1-2").param("userId", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value("已删除该会话"));

        when(chatHistoryService.delete("u1", "bad")).thenReturn(false);
        mockMvc.perform(delete("/api/chat/history/bad").param("userId", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void searchHistory_returnsHits() throws Exception {
        when(chatHistoryService.search("u1", "租房")).thenReturn(List.of(
                Map.of("convId", "3-4", "title", "北京租房", "count", 2)));

        mockMvc.perform(get("/api/chat/search").param("userId", "u1").param("q", "租房"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].title").value("北京租房"));
    }
}