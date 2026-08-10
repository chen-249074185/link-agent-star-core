package com.star.agent.llm;

import com.star.agent.infra.config.AppConfig;
import com.star.agent.llm.client.DeepSeekLlmClient;

import java.util.List;
import java.util.Map;

public class DeepSeekDemo {

    public static void main(String[] args) {
        LlmClient client = new DeepSeekLlmClient(new AppConfig());

        // 定义工具菜单：一个查天气的工具
        ToolSpec getWeather = new ToolSpec(
                "get_weather",
                "查询指定城市的天气",
                Map.of(
                        "type", "object",
                        "properties", Map.of("city", Map.of("type", "string", "description", "城市名")),
                        "required", List.of("city")
                )
        );

        // 带着工具菜单问天气 → 模型应该返回 tool_calls 而不是直接回答
        ChatResponse resp = client.chat(
                List.of(new ChatMessage("user", "北京今天天气怎么样")),
                List.of(getWeather)
        );

        System.out.println("content: " + resp.content());
        System.out.println("toolCalls 数量: " + resp.toolCalls().size());
        for (ToolCall tc : resp.toolCalls()) {
            System.out.println("  工具: " + tc.functionName() + ", 参数: " + tc.arguments());
        }
    }
}