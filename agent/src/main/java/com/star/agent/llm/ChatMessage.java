package com.star.agent.llm;

import java.util.List;

//装对话历史（厂商 API 格式由 client 层负责翻译，此处只存内部结构）
public record ChatMessage(
        String role,
        String content,
        String toolCallId,
        List<ToolCall> toolCalls) {

    public ChatMessage(String role, String content) {
        this(role, content, null, null);
    }

    //系统规则加入历史（意图分类的 system prompt 用）
    public static ChatMessage fromSystem(String content) {
        return new ChatMessage("system", content);
    }

    //用户消息加入历史
    public static ChatMessage fromUser(String content) {
        return new ChatMessage("user", content);
    }

    //调用加入历史
    public static ChatMessage fromAssistant(List<ToolCall> toolCalls) {
        return new ChatMessage("assistant", null, null, toolCalls);
    }

    //结果加入历史
    public static ChatMessage fromToolResult(String toolCallId, String content) {
        return new ChatMessage("tool", content, toolCallId, null);
    }
}