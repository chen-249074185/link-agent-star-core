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
        // stream() 什么都不推，直接回调结束（模拟空流）；切片无拦截器 → userId=null
        doAnswer(inv -> null).when(chatService).stream(anyString(), any(), any(), anyString(), any());

        mockMvc.perform(get("/api/chat/stream").param("message", "你好"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/event-stream"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:streamId")));
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