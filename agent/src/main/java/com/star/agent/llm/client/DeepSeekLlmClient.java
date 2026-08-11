package com.star.agent.llm.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.star.agent.infra.config.AppConfig;
import com.star.agent.llm.ChatMessage;
import com.star.agent.llm.ChatResponse;
import com.star.agent.llm.LlmClient;
import com.star.agent.llm.ToolCall;
import com.star.agent.llm.ToolSpec;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class DeepSeekLlmClient implements LlmClient {

    private final RestClient client;


    public DeepSeekLlmClient(AppConfig appConfig) {
        this.client = RestClient.builder()
                .baseUrl("https://api.deepseek.com")
                .defaultHeader("Authorization", "Bearer " + appConfig.deepseekApiKey())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", "deepseek-chat");
        body.put("messages", messages);
        body.put("tools", buildToolsPayload(tools));

        JsonNode resp = client.post()
                .uri("/chat/completions")
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        JsonNode message = resp.path("choices").get(0).path("message");
        return new ChatResponse(
                message.path("content").asText(),
                parseToolCalls(message.path("tool_calls"))
        );
    }

    // 把内部 ToolSpec 翻译成 API 要求的 {type:function, function:{...}} 格式
    private List<Map<String, Object>> buildToolsPayload(List<ToolSpec> tools) {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (ToolSpec t : tools) {
            Map<String, Object> function = new java.util.LinkedHashMap<>();
            function.put("name", t.name());
            function.put("description", t.description());
            function.put("parameters", t.parameters());

            Map<String, Object> wrapper = new java.util.LinkedHashMap<>();
            wrapper.put("type", "function");
            wrapper.put("function", function);
            payload.add(wrapper);
        }
        return payload;
    }

    //遍历得到工具名,要调什么工具
    private List<ToolCall> parseToolCalls(JsonNode toolCallsNode) {
        List<ToolCall> calls = new ArrayList<>();
        if (toolCallsNode.isMissingNode() || !toolCallsNode.isArray()) {
            return calls;   // 没有 tool_calls → 空列表
        }
        for (JsonNode call : toolCallsNode) {
            calls.add(new ToolCall(
                    call.path("id").asText(),
                    call.path("function").path("name").asText(),
                    call.path("function").path("arguments").asText()
            ));
        }
        return calls;
    }
}
