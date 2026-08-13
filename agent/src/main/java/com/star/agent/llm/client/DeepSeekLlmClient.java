package com.star.agent.llm.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.star.agent.infra.config.AppConfig;
import com.star.agent.llm.ChatMessage;
import com.star.agent.llm.ChatResponse;
import com.star.agent.llm.LlmClient;
import com.star.agent.llm.ToolCall;
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

// =====================================================================
// DeepSeek 厂商实现：实现了 LlmClient 接口（面向接口编程，换厂商只换这个类）
// 职责 = 适配层：把「内部数据结构」翻译成「DeepSeek API 格式」，再反向翻译回来
//   对外提供 2 个能力：
//     1. chat()   非流式：等模型生成完，一次性返回 (content + 工具订单 tool_calls)
//     2. stream() 流式：  请求带 stream=true，响应是 SSE，边生成边回调 onToken
// =====================================================================
@Component
public class DeepSeekLlmClient implements LlmClient {

    // 官方接口地址。做成常量，缺省用；测试想打假服务器就换地址
    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";

    // 为什么 baseUrl / apiKey 要提成字段？
    // 因为两个 HTTP 客户端都要用它们：
    //   非流式 RestClient 要 baseUrl + key 拼默认请求头
    //   流式 HttpClient 也要 baseUrl + key 拼请求头
    // 存在字段里，两个客户端共用，改一处全生效
    private final String baseUrl;
    private final String apiKey;

    // 非流式客户端：Spring 的 RestClient。它会把整个响应体缓存进内存（=一次性拿完整结果）
    private final RestClient client;

    // 流式客户端：JDK 自带的 java.net.http.HttpClient（零新依赖）
    // ofInputStream() 能拿到"活的输入流"，模型生成一段、我们读一段，不用等全部完成
    private final HttpClient streamClient = HttpClient.newHttpClient();

    // ObjectMapper = Jackson 的 JSON 读写器。
    // 非流式 RestClient 会自动把 body 序列化；流式要手动发请求，得自己把 body 变成 JSON 字符串
    private final ObjectMapper mapper = new ObjectMapper();

    // 构造器 1（Spring 用）：只传配置，用官方默认地址
    // this(...) = 调用本类另一个构造器（构造器委托），避免重复写赋值逻辑
    // @Autowired = 类里有多个构造器时，指明 Spring 用哪个注入（否则它找无参构造器会崩）
    @Autowired
    public DeepSeekLlmClient(AppConfig appConfig) {
        this(DEFAULT_BASE_URL, appConfig);
    }

    // 构造器 2：手动指定 baseUrl。
    // 主要给测试用——把地址指向本地假服务器，就不用真打 DeepSeek（省 key 又可控）
    //（原来是包可见，你改成 public 也行，只是公开了入口）
    public DeepSeekLlmClient(String baseUrl, AppConfig appConfig) {
        this.baseUrl = baseUrl;
        this.apiKey = appConfig.deepseekApiKey();   // 从环境变量读 DEEPSEEK_API_KEY
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)   // 每次请求自动带上鉴权头
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    // ==================== 非流式（整段返回） ====================
    @Override
    public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
        // 1. 组装请求体（Java Map → RestClient 自动序列化成 JSON）
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", "deepseek-chat");              // 选模型
        body.put("messages", buildMessagesPayload(messages)); // 对话历史（内部结构→API 格式）
        body.put("tools", buildToolsPayload(tools));         // 工具菜单（翻译成 {type:function,...}）

        // 2. 发 POST，RestClient 帮我们拿到完整 JSON 响应
        JsonNode resp = client.post()
                .uri("/chat/completions")
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        // 3. 从响应里挖数据：choices[0].message 里有 content 和 tool_calls
        JsonNode message = resp.path("choices").get(0).path("message");
        return new ChatResponse(
                message.path("content").asText(),       // 模型回复的正文（没有就是 ""）
                parseToolCalls(message.path("tool_calls")) // 模型要求调的工具清单（没有就是空列表）
        );
    }

    // ==================== 流式（逐 token 回调） ====================
    @Override
    public void stream(List<ChatMessage> messages, List<ToolSpec> tools, Consumer<String> onToken) {
        // 1. 请求体和 chat() 几乎一样，关键差别：多开 stream=true
        //    → 响应不再是"一个大 JSON"，而是 SSE 格式的一串小片段
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", "deepseek-chat");
        body.put("messages", buildMessagesPayload(messages));
        body.put("tools", buildToolsPayload(tools));
        body.put("stream", true);

        // 2. 用 JDK 的 HttpRequest 手动搭请求（RestClient 不支持流式读）
        //    URI.create(...)  把字符串地址转成 URI 对象
        //    BodyPublishers.ofString(json)  POST body = 一串 JSON 字符串
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(body)))
                .build();

        try {
            // 3. send() 会等服务器响应头回来就返回（不等全部内容）
            //    BodyHandlers.ofInputStream() = 拿到"活流"，边生成边读的关键
            HttpResponse<InputStream> resp = streamClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

            // 4. 把字节流包成「按行读」的 BufferedReader，逐行处理 SSE
            //    try-with-resources：读完自动关闭流，不用手动 close
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {   // 读到结尾(null)为止
                    parseSseLine(line, onToken);               // 每一行交给解析器
                }
            }
        } catch (Exception e) {
            // 网络断了 / 服务器报错等 → 转成业务异常，不让崩溃悄悄发生
            throw new RuntimeException("流式请求失败: " + e.getMessage(), e);
        }
    }

    // ==================== 解析一行 SSE ====================
    // SSE 长这样：
    //   data: {"choices":[{"delta":{"content":"你"}}]}
    //   data: {"choices":[{"delta":{"content":"好"}}]}
    //   data: [DONE]                                  ← 结束标记
    // 只认 data: 开头的行，内容是 JSON，增量 token 藏在 choices[0].delta.content
    private void parseSseLine(String line, Consumer<String> onToken) {
        if (line == null || !line.startsWith("data:")) return;   // 空行/心跳行(可能只有冒号)直接跳过
        String data = line.substring(5).trim();                  // 掐掉 "data:" 前 5 个字符，留出 JSON
        if (data.isEmpty() || "[DONE]".equals(data)) return;     // 空数据 / 结束标记 → 不回调直接 return
        try {
            JsonNode node = mapper.readTree(data);               // 字符串 → JSON 树
            // path() 链式挖字段：choices[0].delta.content
            // path(0) 对数组取下标 0；choices 是空数组时返回"缺失节点"，不会崩
            JsonNode content = node.path("choices").path(0).path("delta").path("content");
            // 三重判断：缺失 / 显式 null / 空字符串 → 都不回调
            if (!content.isMissingNode() && !content.isNull() && !content.asText().isEmpty()) {
                onToken.accept(content.asText());   // 关键：把一个 token 片段交给调用方
            }
        } catch (Exception ignore) {
            // 某个块解析失败（如 usage 统计块）→ 跳过这一块，别让一条坏数据中断整条流
        }
    }

    // 手动把请求体 Map 序列化成 JSON 字符串（流式不走 RestClient，要自己转）
    private String toJson(Map<String, Object> body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("请求体序列化失败", e);
        }
    }

    // ==================== 以下 4 个方法 = 适配层翻译 ====================
    // 原则：内部数据结构保持纯净（不带 JSON 注解），厂商 API 的差异全部关在这个类里

    // 内部 ChatMessage → API 消息格式 {role, content?, tool_call_id?, tool_calls?}
    // 注意：content 可能为 null（assistant 只回工具订单时），所以 put 前判空
    private List<Map<String, Object>> buildMessagesPayload(List<ChatMessage> messages) {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (ChatMessage m : messages) {
            Map<String, Object> msg = new java.util.LinkedHashMap<>();
            msg.put("role", m.role());                                  // user / assistant / tool
            if (m.content() != null) msg.put("content", m.content());   // 正文（可能没有）
            if (m.toolCallId() != null) msg.put("tool_call_id", m.toolCallId()); // tool 消息回填的订单号
            if (m.toolCalls() != null) msg.put("tool_calls", buildToolCallsPayload(m.toolCalls())); // 订单
            payload.add(msg);
        }
        return payload;
    }

    // 内部 ToolCall 订单 → API 的 tool_calls 格式 {id, type:"function", function:{name, arguments}}
    // 为什么要包 type:"function"？DeepSeek/OpenAI 规定每个工具必须带这个标记，缺了报 400
    private List<Map<String, Object>> buildToolCallsPayload(List<ToolCall> calls) {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (ToolCall call : calls) {
            Map<String, Object> function = new java.util.LinkedHashMap<>();
            function.put("name", call.functionName());     // 工具名，如 get_weather
            function.put("arguments", call.arguments());   // 参数 JSON 字符串

            Map<String, Object> wrapper = new java.util.LinkedHashMap<>();
            wrapper.put("id", call.id());                  // 订单号（模型发的，还回要原样带）
            wrapper.put("type", "function");
            wrapper.put("function", function);
            payload.add(wrapper);
        }
        return payload;
    }

    // 内部 ToolSpec 工具说明书 → API 的 tools 格式 {type:"function", function:{name, description, parameters}}
    // 这就是"给模型的工具菜单"
    private List<Map<String, Object>> buildToolsPayload(List<ToolSpec> tools) {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (ToolSpec t : tools) {
            Map<String, Object> function = new java.util.LinkedHashMap<>();
            function.put("name", t.name());                 // 工具名
            function.put("description", t.description());   // 作用说明（模型靠它决定要不要调）
            function.put("parameters", t.parameters());     // 参数 JSON Schema

            Map<String, Object> wrapper = new java.util.LinkedHashMap<>();
            wrapper.put("type", "function");
            wrapper.put("function", function);
            payload.add(wrapper);
        }
        return payload;
    }

    // 反向翻译：模型回传的 tool_calls JSON → 内部 ToolCall 订单列表
    private List<ToolCall> parseToolCalls(JsonNode toolCallsNode) {
        List<ToolCall> calls = new ArrayList<>();
        if (toolCallsNode.isMissingNode() || !toolCallsNode.isArray()) {
            return calls;   // 没有 tool_calls → 空列表
        }
        for (JsonNode call : toolCallsNode) {
            calls.add(new ToolCall(
                    call.path("id").asText(),                            // 订单号
                    call.path("function").path("name").asText(),         // 工具名
                    call.path("function").path("arguments").asText()     // 参数 JSON 字符串
            ));
        }
        return calls;
    }
}
