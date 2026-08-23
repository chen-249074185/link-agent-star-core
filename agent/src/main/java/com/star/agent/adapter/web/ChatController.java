package com.star.agent.adapter.web;

import com.star.agent.scene.SceneDetector;
import com.star.agent.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;
    private final SceneDetector sceneDetector;
    private final SseStreamRegistry streamRegistry;

    public ChatController(ChatService chatService, SceneDetector sceneDetector, SseStreamRegistry streamRegistry) {
        this.chatService = chatService;
        this.sceneDetector = sceneDetector;
        this.streamRegistry = streamRegistry;
    }

    // 打字机流：给每条流发"身份证"(streamId)并登记，支持 /stop 中途终止
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String message) {
        String streamId = UUID.randomUUID().toString().substring(0, 8);   // 流身份证
        SseEmitter emitter = new SseEmitter(120_000L);                     // 喇叭（120s 超时）
        AtomicBoolean stopped = new AtomicBoolean(false);                  // 停止标记：/stop 置 true，后台线程感知退出

        streamRegistry.register(streamId, emitter, stopped);               // 登记：/stop 才能找到这条流

        // 生命周期善后：流结束/超时 → 自动从登记处移除（防内存泄漏）+ 置停止标记（后台线程该退了）
        emitter.onCompletion(() -> streamRegistry.remove(streamId));
        emitter.onTimeout(() -> {
            stopped.set(true);
            streamRegistry.remove(streamId);
        });

        String scene = sceneDetector.detect(message).name();               // 三层识别定场景 → 决定走哪个模型

        CompletableFuture.runAsync(() -> {                                 // 后台线程跑"读流"，主线程立刻返回
            try {
                // 先推一条命名事件给前端：前端拿 streamId 才能调 /stop 停这条流
                emitter.send(SseEmitter.event().name("streamId").data(streamId));
                chatService.stream(message, scene, token -> {
                    if (stopped.get()) {                                    // 已点停止：不再推，抛出信号让读流线程退出
                        throw new RuntimeException("stream stopped");
                    }
                    try {
                        emitter.send(SseEmitter.event().data(token));
                    } catch (IOException e) {
                        throw new RuntimeException(e);                      // 浏览器关了/断线 → 也退出
                    }
                });
                emitter.complete();                                         // 正常推完
            } catch (Exception e) {
                // 只有"真报错"才 completeWithError；主动停止/断线不算错，静默结束即可
                if (!"stream stopped".equals(e.getMessage())) {
                    emitter.completeWithError(e);
                }
            }
        });

        return emitter;
    }

    // 流式中途终止：前端点"停止"按钮调这里，按 streamId 找到那条流并终止
    @GetMapping("/stop")
    public String stop(@RequestParam String streamId) {
        boolean stopped = streamRegistry.stop(streamId);
        logger.info("停止流 {}: {}", streamId, stopped ? "已终止" : "未找到(可能已结束)");
        return stopped ? "已停止" : "流不存在或已结束";
    }
}