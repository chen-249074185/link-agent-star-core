package com.star.agent.llm;

import java.util.List;
import java.util.function.Consumer;

//聊天接口协议
public interface LlmClient {

    // 客户端支持的模型名（路由器按 ScenePolicy.model 匹配用）
    String model();

    ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools);

    void stream(List<ChatMessage> messages, List<ToolSpec> tools, Consumer<String> onToken);

    // 流式 + 工具循环合流（W12 10-30）：请求带 tools 走 stream=true，正文逐 token 回调 onToken；
    // 工具订单从 SSE 的 delta.tool_calls 增量拼接后随返回一起给出（调用方静默执行再回填）
    ChatResponse streamWithTools(List<ChatMessage> messages, List<ToolSpec> tools, Consumer<String> onToken);
}