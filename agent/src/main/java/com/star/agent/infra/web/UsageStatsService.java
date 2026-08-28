package com.star.agent.infra.web;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

// 调用统计（管理端"调用统计"Tab 数据源，W12 10-30）
// 内存实现（重启清零，演示用途）：技能调用次数 / 各场景消息数 / 工作流执行次数 / 估算 token
// 埋点位置 = ChatService（chat/streamWithTools 两路 + executeTool 工具执行）
@Component
public class UsageStatsService {

    private final Map<String, Long> toolCalls = new ConcurrentHashMap<>();       // 技能名 → 调用次数
    private final Map<String, Long> sceneMessages = new ConcurrentHashMap<>();   // 场景 → 消息数
    private final Map<String, Long> workflowRuns = new ConcurrentHashMap<>();    // 城市 → 工作流执行次数
    private final Map<String, Long> tokensByModel = new ConcurrentHashMap<>();   // 模型 → 估算 token 数
    private final AtomicLong totalTokens = new AtomicLong();

    // 技能执行次数（治理内核放行后才记）
    public void recordToolCall(String toolName) {
        toolCalls.merge(toolName, 1L, Long::sum);
    }

    // 一条用户消息进入对话（按场景计数）
    public void recordMessage(String scene) {
        sceneMessages.merge(scene, 1L, Long::sum);
    }

    // 工作流模式执行一次（按城市计数）
    public void recordWorkflow(String city) {
        workflowRuns.merge(city, 1L, Long::sum);
    }

    // 估算 token：中英文按 4 字符 ≈ 1 token 粗略折算（展示量级用，非精确计费）
    public void recordTokens(String model, long inputChars, long outputChars) {
        long est = (inputChars + outputChars) / 4 + 1;
        tokensByModel.merge(model, est, Long::sum);
        totalTokens.addAndGet(est);
    }

    // 管理端展示快照
    public Map<String, Object> snapshot() {
        Map<String, Object> snap = new java.util.LinkedHashMap<>();
        snap.put("toolCalls", new java.util.LinkedHashMap<>(toolCalls));
        snap.put("sceneMessages", new java.util.LinkedHashMap<>(sceneMessages));
        snap.put("workflowRuns", new java.util.LinkedHashMap<>(workflowRuns));
        snap.put("tokensByModel", new java.util.LinkedHashMap<>(tokensByModel));
        snap.put("totalTokens", totalTokens.get());
        snap.put("totalToolCalls", toolCalls.values().stream().mapToLong(Long::longValue).sum());
        return snap;
    }
}