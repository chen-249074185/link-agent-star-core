package com.star.agent.llm;

//装对话历史
public record ChatMessage(
        String role,
        String content) {
}