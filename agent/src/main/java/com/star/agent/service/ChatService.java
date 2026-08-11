package com.star.agent.service;

import com.star.agent.llm.ChatMessage;
import com.star.agent.llm.ChatResponse;
import com.star.agent.llm.LlmClient;
import com.star.agent.llm.ToolCall;
import com.star.agent.llm.ToolSpec;
import com.star.agent.tool.ToolDispatcher;
import com.star.agent.tool.ToolExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ChatService {

    private final LlmClient llmClient;
    private final ToolDispatcher dispatcher;
    private final List<ToolExecutor> executors;

    public ChatService(LlmClient llmClient, ToolDispatcher dispatcher, List<ToolExecutor> executors) {
        this.llmClient = llmClient;
        this.dispatcher = dispatcher;
        this.executors = executors;
    }

    private List<ToolSpec> buildTools() {
        List<ToolSpec> tools = new ArrayList<>();
        for (ToolExecutor e : executors) {
            tools.add(new ToolSpec(e.name(), e.description(), e.parameters()));
        }
        return tools;
    }

    private Map<String, Object> toApiToolCall(ToolCall call) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", call.functionName());
        function.put("arguments", call.arguments());

        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("id", call.id());
        wrapper.put("type", "function");
        wrapper.put("function", function);
        return wrapper;
    }

    public String chat(String userMessage) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("user", userMessage));
        List<ToolSpec> tools = buildTools();

        for (int round = 0; round < 5; round++) {
            ChatResponse resp = llmClient.chat(messages, tools);

            if (resp.toolCalls().isEmpty()) {
                return resp.content();
            }

            List<Map<String, Object>> apiCalls = new ArrayList<>();
            for (ToolCall call : resp.toolCalls()) {
                apiCalls.add(toApiToolCall(call));
            }
            messages.add(ChatMessage.fromAssistant(apiCalls));

            for (ToolCall call : resp.toolCalls()) {
                String result = dispatcher.dispatch(call.functionName(), call.arguments());
                messages.add(ChatMessage.fromToolResult(call.id(), result));
            }
        }
        return "工具调用次数过多，请换个问法";
    }
}
