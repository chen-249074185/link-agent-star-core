package com.star.agent.service;

import com.star.agent.llm.ChatMessage;
import com.star.agent.llm.ChatResponse;
import com.star.agent.llm.LlmClient;
import com.star.agent.llm.ToolCall;
import com.star.agent.llm.ToolSpec;
import com.star.agent.tool.ToolDispatcher;
import com.star.agent.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Component
public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    private final LlmClient llmClient;
    private final ToolDispatcher dispatcher;
    private final List<ToolExecutor> executors;

    public ChatService(LlmClient llmClient, ToolDispatcher dispatcher, List<ToolExecutor> executors) {
        this.llmClient = llmClient;
        this.dispatcher = dispatcher;
        this.executors = executors;
    }

    //制作“工具菜单”
    private List<ToolSpec> buildTools() {
        List<ToolSpec> tools = new ArrayList<>();
        for (ToolExecutor e : executors) {
            tools.add(new ToolSpec(e.name(), e.description(), e.parameters()));
        }
        return tools;
    }

    public String chat(String userMessage) {
        logger.info("开始对话: {}", userMessage);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("user", userMessage));
        List<ToolSpec> tools = buildTools();

        for (int round = 0; round < 5; round++) {
            ChatResponse resp = llmClient.chat(messages, tools);

            if (resp.toolCalls().isEmpty()) {
                logger.info("对话结束，共{}轮", round + 1);
                return resp.content();
            }

            messages.add(ChatMessage.fromAssistant(resp.toolCalls()));

            for (ToolCall call : resp.toolCalls()) {
                String result = dispatcher.dispatch(call.functionName(), call.arguments());
                messages.add(ChatMessage.fromToolResult(call.id(), result));
            }
        }
        logger.warn("对话超出工具循环上限5轮");
        return "工具调用次数过多，请换个问法";
    }

public void stream(String userMessage, Consumer<String> onToken){
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("user", userMessage));
        llmClient.stream(messages,List.of(),onToken);
    }
}
