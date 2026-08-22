package com.star.agent.llm.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.star.agent.infra.config.AppConfig;
import com.star.agent.llm.ChatMessage;
import com.star.agent.llm.ChatResponse;
import com.star.agent.llm.LlmClient;
import com.star.agent.llm.ToolSpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

// 阿里云 DashScope 实现（OpenAI 兼容模式）：LlmClient 接口第二个厂商
// 用途：意图分类（qwen-turbo + JSON 结构化输出），只走非流式
@Component
public class DashScopeLlmClient implements LlmClient {

    // ① OpenAI 兼容模式的官方地址（注意末尾 /v1，和 DeepSeek 不同）
    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    private final String baseUrl;
    private final String apiKey;
    private final RestClient client;

    @Autowired
    public DashScopeLlmClient(AppConfig appConfig) {
        this(DEFAULT_BASE_URL, appConfig);
    }

    // 测试用构造器：把地址指向本地假服务器
    public DashScopeLlmClient(String baseUrl, AppConfig appConfig) {
        this.baseUrl = baseUrl;
        this.apiKey = appConfig.dashScopeApiKey();   // ② 读 DASHSCOPE_API_KEY
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", "qwen-turbo");                         // ③ 选模型
        body.put("messages", buildMessagesPayload(messages));    // 对话历史
        body.put("response_format", Map.of("type", "json_object")); // ④ 强制 JSON 输出

        JsonNode resp = client.post()
                .uri("/chat/completions")
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        JsonNode message = resp.path("choices").get(0).path("message");
        return new ChatResponse(
                message.path("content").asText(),  // 模型回的 JSON 字符串（如 {"scene":"DATA","confidence":0.92}）
                new ArrayList<>()                  // 分类不调工具
        );
    }

    // ⑤ 意图分类只走 chat()；流式由 DeepSeek 版提供（YAGNI）
    @Override
    public void stream(List<ChatMessage> messages, List<ToolSpec> tools, Consumer<String> onToken) {
        throw new UnsupportedOperationException("DashScope 版暂不支持流式（意图分类用不到）");
    }

    // 内部 ChatMessage → API 消息格式（精简版：只要 role/content）
    private List<Map<String, Object>> buildMessagesPayload(List<ChatMessage> messages) {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (ChatMessage m : messages) {
            Map<String, Object> msg = new java.util.LinkedHashMap<>();
            msg.put("role", m.role());
            if (m.content() != null) msg.put("content", m.content());
            payload.add(msg);
        }
        return payload;
    }
}