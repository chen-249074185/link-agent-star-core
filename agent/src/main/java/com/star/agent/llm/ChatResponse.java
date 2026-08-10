package com.star.agent.llm;

import java.util.List;

//模型的回复内容 + 模型要调的工具
public record ChatResponse(
        String content,
        List<ToolCall> toolCalls) {
}