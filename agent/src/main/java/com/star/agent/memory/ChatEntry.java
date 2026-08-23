package com.star.agent.memory;

// 记忆条目：一条轻量对话消息（不存工具调用细节，只要"说了什么"）
public record ChatEntry(
        String role,     // user / assistant / system（压缩后会有 system 摘要）
        String content   // 消息正文
) {
}