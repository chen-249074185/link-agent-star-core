package com.star.agent.scene;

import com.star.agent.infra.config.AppConfig;
import com.star.agent.llm.client.DashScopeLlmClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

// IntentClassifier 单测：本地假 DashScope 服务器（JDK HttpServer，零依赖）
class IntentClassifierTest {

    private HttpServer server;
    private IntentClassifier classifier;
    // 记录假服务器收到的请求体（验证 prompt 里确实带了最近消息）
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    // 假服务器返回的 content（每个用例可改，模拟不同响应）
    private final AtomicReference<String> fakeContent = new AtomicReference<>(
            "{\"scene\":\"DATA\",\"confidence\":0.92}");

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastRequestBody.set(body);
            String json = "{\"choices\":[{\"message\":{\"content\":\"" + fakeContent.get().replace("\"", "\\\"") + "\"}}]}";
            byte[] resp = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        // 假服务器地址 + 真实 AppConfig（key 为空也无妨，只测分类逻辑）
        DashScopeLlmClient client = new DashScopeLlmClient("http://127.0.0.1:" + server.getAddress().getPort(), new AppConfig());
        classifier = new IntentClassifier(client);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    // 模型高置信度判定 DATA → 分类器返回 DATA
    @Test
    void classifyHighConfidenceDataScene() {
        assertEquals(Scene.DATA, classifier.classify(List.of("你好", "北京今天天气", "北京天气")));
    }

    // prompt 里确实带了最近 3 条消息（最后一条在最前的是 system，之后是消息）
    @Test
    void promptContainsRecentMessages() {
        classifier.classify(List.of("第一条", "第二条", "最新一条"));
        String body = lastRequestBody.get();
        assertEquals(true, body.contains("第一条"));
        assertEquals(true, body.contains("第二条"));
        assertEquals(true, body.contains("最新一条"));
        assertEquals(true, body.contains("qwen-turbo"));
        assertEquals(true, body.contains("json_object"));
    }

    // 置信度 <0.7 → 回退 CHAT
    @Test
    void lowConfidenceFallsBackToChat() {
        fakeContent.set("{\"scene\":\"DATA\",\"confidence\":0.5}");
        assertEquals(Scene.CHAT, classifier.classify(List.of("随便聊聊")));
    }

    // 场景名不是合法枚举 → valueOf 异常 → 回退 CHAT
    @Test
    void invalidSceneFallsBackToChat() {
        fakeContent.set("{\"scene\":\"HACKER\",\"confidence\":0.95}");
        assertEquals(Scene.CHAT, classifier.classify(List.of("随便聊聊")));
    }

    // 模型返回的不是 JSON → 解析失败 → 回退 CHAT
    @Test
    void garbageResponseFallsBackToChat() {
        fakeContent.set("我不是JSON，我是乱码");
        assertEquals(Scene.CHAT, classifier.classify(List.of("随便聊聊")));
    }

    // 假服务器停掉（网络异常）→ 不崩 → 回退 CHAT
    @Test
    void networkErrorFallsBackToChat() {
        server.stop(0);
        assertEquals(Scene.CHAT, classifier.classify(List.of("随便聊聊")));
    }

    // 高置信度合法场景 → 正常判定
    @Test
    void highConfidenceCreationScene() {
        fakeContent.set("{\"scene\":\"CREATION\",\"confidence\":0.9}");
        assertEquals(Scene.CREATION, classifier.classify(List.of("帮我写首诗")));
    }
}