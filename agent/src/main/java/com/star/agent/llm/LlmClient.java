package com.star.agent.llm;

import java.util.List;

//聊天接口协议
public interface LlmClient {
    ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools);
}