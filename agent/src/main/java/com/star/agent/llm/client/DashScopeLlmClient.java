package com.star.agent.llm.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.star.agent.infra.config.AppConfig;
import com.star.agent.llm.ChatMessage;
import com.star.agent.llm.ChatResponse;
import com.star.agent.llm.LlmClient;
import com.star.agent.llm.ToolSpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

// 阿里云 DashScope 实现（OpenAI 兼容模式）：LlmClient 接口第二个厂商
// 用途：意图分类（qwen-turbo + JSON 结构化输出）+ 创作对话（qwen-max）
// 一个类服务多个模型：model 由构造器指定（qwen-turbo/qwen-plus/qwen-max），默认 qwen-turbo
@Component
public class DashScopeLlmClient implements LlmClient {

    // OpenAI 兼容模式的官方地址（注意末尾 /v1，和 DeepSeek 不同）
    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    // 意图分类默认用的轻量模型
    private static final String DEFAULT_MODEL = "qwen-turbo";

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final RestClient client;
    private final HttpClient streamClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public DashScopeLlmClient(AppConfig appConfig) {
        this(DEFAULT_BASE_URL, DEFAULT_MODEL, appConfig);
    }

    // 测试用构造器：地址指向本地假服务器
    public DashScopeLlmClient(String baseUrl, String model, AppConfig appConfig) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = appConfig.dashScopeApiKey();
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    // 兼容旧测试签名（只传 baseUrl）——AppConfig 的 key 为空也无妨
    public DashScopeLlmClient(String baseUrl, AppConfig appConfig) {
        this(baseUrl, DEFAULT_MODEL, appConfig);
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", buildMessagesPayload(messages));
        // 强制 JSON 结构化输出（qwen 系列兼容模式支持 json_object）
        body.put("response_format", Map.of("type", "json_object"));

        JsonNode resp = client.post()
                .uri("/chat/completions")
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        JsonNode message = resp.path("choices").get(0).path("message");
        return new ChatResponse(
                message.path("content").asText(),
                new ArrayList<>()   // 分类/对话不调工具，toolCalls 恒为空
        );
    }

    // 流式：SSE 格式与 OpenAI/DeepSeek 完全一致（data: {...} + [DONE]），照抄 DeepSeek 版逻辑
    @Override
    public void stream(List<ChatMessage> messages, List<ToolSpec> tools, Consumer<String> onToken) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", buildMessagesPayload(messages));
        body.put("stream", true);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(body)))
                .build();

        try {
            HttpResponse<InputStream> resp = streamClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    parseSseLine(line, onToken);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("DashScope 流式请求失败: " + e.getMessage(), e);
        }
    }

    // 流式 + 工具循环合流：DashScope 不走工具循环（qwen 分类/创作纯对话），直接拒绝
    @Override
    public ChatResponse streamWithTools(List<ChatMessage> messages, List<ToolSpec> tools, Consumer<String> onToken) {
        throw new UnsupportedOperationException("DashScope 不支持流式工具调用（工具循环固定走 DeepSeek）");
    }

    // 解析一行 SSE：data: {"choices":[{"delta":{"content":"..."}}]} / data: [DONE]
    private void parseSseLine(String line, Consumer<String> onToken) {
        if (line == null || !line.startsWith("data:")) return;
        String data = line.substring(5).trim();
        if (data.isEmpty() || "[DONE]".equals(data)) return;
        try {
            JsonNode node = mapper.readTree(data);
            JsonNode content = node.path("choices").path(0).path("delta").path("content");
            if (!content.isMissingNode() && !content.isNull() && !content.asText().isEmpty()) {
                onToken.accept(content.asText());
            }
        } catch (Exception ignore) {
            // 单个块解析失败（如 usage 统计块）跳过，不中断整条流
        }
    }

    private String toJson(Map<String, Object> body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("请求体序列化失败", e);
        }
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