package com.star.agent.adapter.web;

import com.star.agent.scene.Scene;
import com.star.agent.scene.SceneDetector;
import com.star.agent.service.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
// ChatController 端点测试：/stream 发 streamId 事件；/stop 终止并移除
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

    @Test
    void stream_returnsSseWithStreamIdEvent() throws Exception {
        when(sceneDetector.detect(anyString())).thenReturn(Scene.CHAT);
        // stream() 什么都不推，直接回调结束（模拟空流）
        doAnswer(inv -> {
            inv.getArgument(2, java.util.function.Consumer.class);   // 不推 token
            return null;
        }).when(chatService).stream(anyString(), anyString(), any());

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
}