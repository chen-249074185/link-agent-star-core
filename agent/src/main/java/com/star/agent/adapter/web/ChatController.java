package com.star.agent.adapter.web;

import com.star.agent.scene.SceneDetector;
import com.star.agent.service.ChatService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final ChatService chatService;
    private final SceneDetector sceneDetector;

    public ChatController(ChatService chatService, SceneDetector sceneDetector) {
        this.chatService = chatService;
        this.sceneDetector = sceneDetector;
    }

    //告诉浏览器"我这接口返回的是事件流
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String message) {
        SseEmitter emitter = new SseEmitter(120_000L);      // ① 造个喇叭
        String scene = sceneDetector.detect(message).name(); // 三层识别定场景 → 决定走哪个模型

        CompletableFuture.runAsync(() -> {                   // ② 放新线程跑，把"读流"这个活丢到后台线程去做
            try {
                chatService.stream(message, scene, token -> {        // ③ 调你写的 stream()
                    try {
                        emitter.send(SseEmitter.event().data(token));  // ④ 收到一个字就推一个字
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
                emitter.complete();                          // ⑤ 流读完了，正常结束
            } catch (Exception e) {
                emitter.completeWithError(e);                // ⑥ 出错就报错结束
            }
        });

        return emitter;                                      // ⑦ 喇叭立刻还给 Spring，后台慢慢推
    }
}
