package com.star.agent.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

//装对话历史
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessage(
        String role,
        String content,
        @JsonProperty("tool_call_id") String toolCallId,
        @JsonProperty("tool_calls") List<Map<String, Object>> toolCalls) {

    public ChatMessage(String role, String content) {
        this(role, content, null, null);
    }

    //调用加入历史
    public static ChatMessage fromAssistant(List<Map<String, Object>> toolCalls) {
        return new ChatMessage("assistant", null, null, toolCalls);
    }

    //结果加入历史
    public static ChatMessage fromToolResult(String toolCallId, String content) {
        return new ChatMessage("tool", content, toolCallId, null);
    }
}