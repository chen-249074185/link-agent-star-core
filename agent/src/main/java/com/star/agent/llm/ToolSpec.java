package com.star.agent.llm;

import java.util.Map;

//工具说明书
public record ToolSpec(
        String name,
        String description,
        Map<String, Object> parameters) {
}