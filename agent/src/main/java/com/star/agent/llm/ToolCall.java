package com.star.agent.llm;

//模型要用哪个工具 "订单"
public record ToolCall(
        String id,
        String functionName,
        String arguments) {
}