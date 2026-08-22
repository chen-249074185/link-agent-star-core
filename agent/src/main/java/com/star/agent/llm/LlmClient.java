package com.star.agent.llm;

import java.util.List;
import java.util.function.Consumer;

//聊天接口协议
public interface LlmClient {

    // 客户端支持的模型名（路由器按 ScenePolicy.model 匹配用）
    String model();

    ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools);

    void stream(List<ChatMessage> messages, List<ToolSpec> tools, Consumer<String> onToken);
}