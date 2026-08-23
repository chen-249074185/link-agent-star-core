package com.star.agent.adapter.web;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

// SSE 流登记处：管理所有进行中的流（streamId → 流任务）
// 用途：
//   ① /stream 创建流时登记 → /stop 按 id 找到流并终止
//   ② 流结束/超时时自动移除（onCompletion/onTimeout 回调），防泄漏
@Component
public class SseStreamRegistry {

    private final Map<String, StreamTask> streams = new ConcurrentHashMap<>();

    // 登记一个新流：streamId 全局唯一（前端拿它调 /stop）
    public void register(String streamId, SseEmitter emitter, AtomicBoolean stopped) {
        streams.put(streamId, new StreamTask(streamId, emitter, stopped));
    }

    // 终止指定流：置停止标记（后台线程下个 token 感知退出）+ 通知浏览器流结束
    public boolean stop(String streamId) {
        StreamTask task = streams.get(streamId);
        if (task == null) {
            return false;   // 没有这个流（已结束或不存在）
        }
        task.stopped().set(true);
        task.emitter().complete();
        streams.remove(streamId);
        return true;
    }

    // 流自然结束/超时后从登记处移除（由 onCompletion/onTimeout 调用）
    public void remove(String streamId) {
        streams.remove(streamId);
    }

    public boolean contains(String streamId) {
        return streams.containsKey(streamId);
    }

    public int size() {
        return streams.size();
    }

    // 一条流任务：id + 喇叭 + 停止标记（后台线程轮询它决定要不要退出）
    public record StreamTask(String streamId, SseEmitter emitter, AtomicBoolean stopped) {
    }
}