package com.star.agent.adapter;

import com.star.agent.message.AgentMessage;
import com.star.agent.message.MediaType;
import com.star.agent.message.MessageSource;
import com.star.agent.scene.SceneDetector;
import com.star.agent.service.ChatService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// MessageDispatcher 双线程管线单测：mock adapter，验证 队列转发/去重/断线重登/单条失败不影响后续
// 异步场景：dispatcher 内部两条线程，断言用 CountDownLatch + 超时（不能依赖时序）
class MessageDispatcherTest {

    private MessageAdapter adapter;
    private SceneDetector sceneDetector;
    private ChatService chatService;
    private ReconnectHandler reconnectHandler;
    private MessageDispatcher dispatcher;

    private AgentMessage msg(String id, String text) {
        return new AgentMessage(MessageSource.WECHAT, "u1", text, MediaType.TEXT, null, id, Instant.now());
    }

    @BeforeEach
    void setUp() {
        adapter = mock(MessageAdapter.class);
        sceneDetector = mock(SceneDetector.class);
        chatService = mock(ChatService.class);
        reconnectHandler = mock(ReconnectHandler.class);
        dispatcher = new MessageDispatcher(adapter, sceneDetector, chatService, reconnectHandler);
        when(sceneDetector.detect(anyString())).thenReturn(com.star.agent.scene.Scene.CHAT);
    }

    @AfterEach
    void tearDown() {
        dispatcher.stop();
    }

    // 队列转发：fetcher 收到 → processor 处理 → 回复发回通道
    @Test
    void message_flowsThroughPipeline() throws Exception {
        when(adapter.receive())
                .thenReturn(msg("m1", "你好"))
                .thenReturn(null);   // 之后空转

        when(chatService.chat(eq("你好"), eq("u1"), eq("CHAT"))).thenReturn("回复");
        CountDownLatch sent = new CountDownLatch(1);
        doAnswer(inv -> {           // 等"回复真的发出去了"再断言（countDown 放在 send，不在 chat）
            sent.countDown();
            return null;
        }).when(adapter).send(eq("u1"), eq("回复"));

        dispatcher.start();

        assertTrue(sent.await(5, TimeUnit.SECONDS), "回复应发出");
        verify(adapter).send("u1", "回复");
    }

    // 去重：同一 messageId 出现两次 → 只处理一次
    @Test
    void duplicateMessageId_processedOnce() throws Exception {
        when(adapter.receive())
                .thenReturn(msg("m1", "第一条"))
                .thenReturn(msg("m1", "第一条"))   // 长轮询重复返回同一条
                .thenReturn(null);

        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger chatCalls = new AtomicInteger();
        doAnswer(inv -> {
            chatCalls.incrementAndGet();
            done.countDown();
            return "ok";
        }).when(chatService).chat(eq("第一条"), eq("u1"), eq("CHAT"));

        dispatcher.start();

        assertTrue(done.await(5, TimeUnit.SECONDS), "第一条应被处理");
        Thread.sleep(500);   // 留出重复消息被跳过的时间窗口
        assertEquals(1, chatCalls.get(), "重复消息只处理一次");
        verify(adapter).send("u1", "ok");   // 只发一次回复（重复那条被跳过，不会发第二次）
    }

    // 断线自动重登：receive 抛异常 → 触发 ReconnectHandler
    @Test
    void receiveFailure_triggersReconnect() throws Exception {
        when(adapter.receive()).thenThrow(new RuntimeException("连接断开"));

        CountDownLatch reconnected = new CountDownLatch(1);
        doAnswer(inv -> {
            reconnected.countDown();
            return null;
        }).when(reconnectHandler).onDisconnected();

        dispatcher.start();

        assertTrue(reconnected.await(5, TimeUnit.SECONDS), "断线应触发重登回调");
        verify(reconnectHandler).onDisconnected();
    }

    // 单条处理失败不影响后续：第一条抛异常 → 第二条照常处理
    @Test
    void processingFailure_doesNotBlockNext() throws Exception {
        when(adapter.receive())
                .thenReturn(msg("m1", "会失败的消息"))
                .thenReturn(msg("m2", "正常消息"))
                .thenReturn(null);

        doThrow(new RuntimeException("LLM 挂了")).when(chatService).chat(eq("会失败的消息"), anyString(), anyString());
        when(chatService.chat(eq("正常消息"), eq("u1"), eq("CHAT"))).thenReturn("正常回复");
        CountDownLatch sent = new CountDownLatch(1);
        doAnswer(inv -> {           // 等"正常消息的回复发出"（失败那条不会走到 send）
            sent.countDown();
            return null;
        }).when(adapter).send(eq("u1"), eq("正常回复"));

        dispatcher.start();

        assertTrue(sent.await(5, TimeUnit.SECONDS), "失败后的消息仍应被处理");
        verify(adapter).send("u1", "正常回复");
    }
}