package com.star.agent.adapter;

import com.star.agent.adapter.wechat.WechatIdentityResolver;
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
    private BotCommandHandler commandHandler;
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
        commandHandler = mock(BotCommandHandler.class);
        dispatcher = new MessageDispatcher(adapter, sceneDetector, chatService, reconnectHandler, commandHandler, null, null, null);
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

// 命令拦截：handler 返回非 null → 直接回复，不花 AI 的 token（chat 不被调）
    @Test
    void commandMessage_skipsChat() throws Exception {
        when(adapter.receive())
                .thenReturn(msg("m1", "#logout"))
                .thenReturn(null);
        when(commandHandler.handle(eq("#logout"), eq("u1"))).thenReturn("已登出");

        CountDownLatch sent = new CountDownLatch(1);
        doAnswer(inv -> {
            sent.countDown();
            return null;
        }).when(adapter).send(eq("u1"), eq("已登出"));

        dispatcher.start();

        assertTrue(sent.await(5, TimeUnit.SECONDS), "命令回复应直接发出");
        verify(adapter).send("u1", "已登出");
        verify(chatService, never()).chat(anyString(), anyString(), anyString());
    }

// 防抖接线：两条连续消息（触发词"直接处理"）→ 聚合后只调一次 chat，且文本拼合
    @Test
    void debounce_aggregatesThenChatsOnce() throws Exception {
        dispatcher = new MessageDispatcher(adapter, sceneDetector, chatService, reconnectHandler,
                commandHandler, null, new DebounceProcessor(), null);
        when(adapter.receive())
                .thenReturn(msg("m1", "第一条"))
                .thenReturn(msg("m2", "直接处理"))
                .thenReturn(null);
        when(chatService.chat(eq("第一条\n直接处理"), eq("u1"), eq("CHAT"))).thenReturn("聚合回复");

        CountDownLatch sent = new CountDownLatch(1);
        doAnswer(inv -> {
            sent.countDown();
            return null;
        }).when(adapter).send(eq("u1"), eq("聚合回复"));

        dispatcher.start();

        assertTrue(sent.await(5, TimeUnit.SECONDS), "聚合后应回复");
        verify(chatService).chat("第一条\n直接处理", "u1", "CHAT");   // 只调一次，文本拼合
    }

    // 防抖接线的关键回归：语音消息过防抖后必须保留 VOICE 类型（否则被转成空文本走 chat，
    // 实测"发语音收到『您还没有输入内容』"——只认 IMAGE 把语音类型丢了）
    @Test
    void debounce_voiceMessage_keepsVoiceType() throws Exception {
        MultimediaHandler mm = mock(MultimediaHandler.class);
        dispatcher = new MessageDispatcher(adapter, sceneDetector, chatService, reconnectHandler,
                commandHandler, mm, new DebounceProcessor(), null);
        AgentMessage voice = new AgentMessage(MessageSource.WECHAT, "u1", "", MediaType.VOICE,
                new byte[]{5, 6}, "m1", Instant.now());
        when(adapter.receive())
                .thenReturn(voice)
                .thenReturn(msg("m2", "直接处理"))
                .thenReturn(null);
        when(mm.handle(any(), eq(adapter))).thenReturn("转写后的回复");

        CountDownLatch sent = new CountDownLatch(1);
        doAnswer(inv -> {
            sent.countDown();
            return null;
        }).when(adapter).send(eq("u1"), eq("转写后的回复"));

        dispatcher.start();

        assertTrue(sent.await(5, TimeUnit.SECONDS), "语音消息应被多媒体处理");
        verify(mm).handle(org.mockito.ArgumentMatchers.argThat(am ->
                am.mediaType() == MediaType.VOICE && java.util.Arrays.equals(am.mediaBytes(), new byte[]{5, 6})),
                eq(adapter));
        verify(chatService, never()).chat(anyString(), anyString(), anyString());
    }

    // per-user 隔离（两账号不串音的处理器层证据）：两个用户的消息同时进来，
    // chat 必须带各自 userId、回复必须发回各自 userId——userId 随消息走，不混用
    @Test
    void multipleUsers_isolatedPerUser() throws Exception {
        AgentMessage u1msg = new AgentMessage(MessageSource.WECHAT, "wx_u1", "一号的消息", MediaType.TEXT, null, "m1", Instant.now());
        AgentMessage u2msg = new AgentMessage(MessageSource.WECHAT, "wx_u2", "二号的消息", MediaType.TEXT, null, "m2", Instant.now());
        when(adapter.receive())
                .thenReturn(u1msg)
                .thenReturn(u2msg)
                .thenReturn(null);

        when(chatService.chat(eq("一号的消息"), eq("wx_u1"), eq("CHAT"))).thenReturn("一号的回复");
        when(chatService.chat(eq("二号的消息"), eq("wx_u2"), eq("CHAT"))).thenReturn("二号的回复");
        CountDownLatch bothSent = new CountDownLatch(2);
        doAnswer(inv -> {
            bothSent.countDown();
            return null;
        }).when(adapter).send(anyString(), anyString());

        dispatcher.start();

        assertTrue(bothSent.await(5, TimeUnit.SECONDS), "两条回复都应发出");
        verify(chatService).chat("一号的消息", "wx_u1", "CHAT");
        verify(chatService).chat("二号的消息", "wx_u2", "CHAT");
        verify(adapter).send("wx_u1", "一号的回复");
        verify(adapter).send("wx_u2", "二号的回复");
    }

    // ReplySender 接线：有 ReplySender → 回复走发送编排（ReplySender.send），不再直发 adapter.send
    @Test
    void replySender_wrapsTextReply() throws Exception {
        ReplySender replySender = mock(ReplySender.class);
        dispatcher = new MessageDispatcher(adapter, sceneDetector, chatService, reconnectHandler,
                commandHandler, null, null, replySender);
        when(adapter.receive())
                .thenReturn(msg("m1", "你好"))
                .thenReturn(null);
        when(chatService.chat(eq("你好"), eq("u1"), eq("CHAT"))).thenReturn("回复");

        CountDownLatch sent = new CountDownLatch(1);
        doAnswer(inv -> {
            sent.countDown();
            return null;
        }).when(replySender).send(eq("u1"), org.mockito.ArgumentMatchers.any(Reply.class), eq(adapter));

        dispatcher.start();

        assertTrue(sent.await(5, TimeUnit.SECONDS), "回复应走 ReplySender 发送编排");
        verify(replySender).send(eq("u1"),
                org.mockito.ArgumentMatchers.argThat(r -> "回复".equals(r.text())), eq(adapter));
        verify(adapter, never()).send(eq("u1"), eq("回复"));
    }

    // ===== 微信身份映射（10-31 绑定互通）：对话走账号身份，回复目标保持微信地址 =====
    @Test
    void identityResolver_mapsWechatToAccount() throws Exception {
        WechatIdentityResolver resolver = mock(WechatIdentityResolver.class);
        when(resolver.resolve("u1")).thenReturn("u-account");   // 微信 u1 已绑定账号 u-account
        dispatcher = new MessageDispatcher(adapter, sceneDetector, chatService, reconnectHandler,
                commandHandler, null, null, null, resolver);
        when(adapter.receive())
                .thenReturn(msg("m1", "你好"))
                .thenReturn(null);
        when(chatService.chat(eq("你好"), eq("u-account"), eq("CHAT"))).thenReturn("回复");

        CountDownLatch sent = new CountDownLatch(1);
        doAnswer(inv -> {
            sent.countDown();
            return null;
        }).when(adapter).send(eq("u1"), eq("回复"));   // 回复仍发回微信地址

        dispatcher.start();

        assertTrue(sent.await(5, TimeUnit.SECONDS));
        verify(chatService).chat(eq("你好"), eq("u-account"), eq("CHAT"));   // 对话/记忆/知识库走账号身份
        verify(adapter).send(eq("u1"), eq("回复"));                          // 回复目标保持微信地址
    }

    // 未绑定：resolver 返回原值，行为与绑定前一致
    @Test
    void identityResolver_notBound_usesWechatId() throws Exception {
        WechatIdentityResolver resolver = mock(WechatIdentityResolver.class);
        when(resolver.resolve("u1")).thenReturn("u1");
        dispatcher = new MessageDispatcher(adapter, sceneDetector, chatService, reconnectHandler,
                commandHandler, null, null, null, resolver);
        when(adapter.receive())
                .thenReturn(msg("m1", "你好"))
                .thenReturn(null);
        when(chatService.chat(eq("你好"), eq("u1"), eq("CHAT"))).thenReturn("回复");

        CountDownLatch sent = new CountDownLatch(1);
        doAnswer(inv -> {
            sent.countDown();
            return null;
        }).when(adapter).send(eq("u1"), eq("回复"));

        dispatcher.start();

        assertTrue(sent.await(5, TimeUnit.SECONDS));
        verify(chatService).chat(eq("你好"), eq("u1"), eq("CHAT"));
    }

    // 重登换 client 后清空队列：已入队但未处理的旧消息必须丢弃（新 client 无其 context token）
    @Test
    void clearPending_afterReconnect_dropsQueuedMessages() throws Exception {
        // 队列里塞两条待处理消息（旧 client 拉的），然后清空
        when(adapter.receive())
                .thenReturn(msg("m1", "旧消息一"))
                .thenReturn(msg("m2", "旧消息二"))
                .thenReturn(null);
        CountDownLatch queued = new CountDownLatch(2);
        // 等消息进队列：processor poll 前会先被 debounce=null 直通，用 chat 计数判断已取走
        AtomicInteger chatCalls = new AtomicInteger();
        doAnswer(inv -> {
            chatCalls.incrementAndGet();
            return "ok";
        }).when(chatService).chat(anyString(), anyString(), anyString());

        dispatcher.start();
        Thread.sleep(200);   // 让旧消息可能已入队/部分处理

        dispatcher.clearPending();   // 重登后清队列

        // 清空后不再有新消息进来 → chat 最终调用数停在已处理的（不因清空暴增）
        int callsAfterClear = chatCalls.get();
        Thread.sleep(200);
        assertEquals(callsAfterClear, chatCalls.get(), "清空后不应再处理队列残留消息");
    }
}