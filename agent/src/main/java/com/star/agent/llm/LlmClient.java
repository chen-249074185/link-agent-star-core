package com.star.agent.llm;

import java.util.List;
import java.util.function.Consumer;

//聊天接口协议
public interface LlmClient {
    ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools);

    void stream(List<ChatMessage> messages, List<ToolSpec> tools, Consumer<String> onToken);
}