package com.star.agent.adapter.web;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// SseStreamRegistry 单测：登记/停止/移除/不存在
class SseStreamRegistryTest {

    private final SseStreamRegistry registry = new SseStreamRegistry();

    @Test
    void register_containsStream() {
        registry.register("abc123", new SseEmitter(), new AtomicBoolean(false));
        assertTrue(registry.contains("abc123"));
        assertEquals(1, registry.size());
    }

    @Test
    void stop_setsFlagAndCompletesAndRemoves() {
        SseEmitter emitter = new SseEmitter();
        AtomicBoolean stopped = new AtomicBoolean(false);
        registry.register("abc123", emitter, stopped);

        assertTrue(registry.stop("abc123"));
        assertTrue(stopped.get());            // 停止标记已置：后台线程下个 token 感知退出
        assertFalse(registry.contains("abc123"));   // 已移除：防泄漏
    }

    @Test
    void stop_unknownId_returnsFalse() {
        assertFalse(registry.stop("ghost"));
    }

    @Test
    void remove_cleansUp() {
        registry.register("abc123", new SseEmitter(), new AtomicBoolean(false));
        registry.remove("abc123");
        assertFalse(registry.contains("abc123"));
    }
}