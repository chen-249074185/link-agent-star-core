package com.star.rag.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// EmbeddingClient 集成测试：本地假 HTTP 服务器模拟 DashScope（验证批量/分批/重试）
class EmbeddingClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final List<Integer> requestInputSizes = new ArrayList<>();   // 每个请求的 input 数组长度
    private int failTimes;                                               // 前 N 个请求回 429（模拟限流）

    @BeforeEach
    void setUp() throws IOException {
        requestCount.set(0);
        requestInputSizes.clear();
        failTimes = 0;

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            JsonNode req = mapper.readTree(exchange.getRequestBody().readAllBytes());
            int n = req.path("input").size();          // 数 input 数组元素个数
            requestInputSizes.add(n);

            if (requestCount.incrementAndGet() <= failTimes) {   // 命中"该失败"配额
                byte[] err = "{\"error\":\"rate limit\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(429, err.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(err);
                }
                return;
            }
            byte[] resp = embeddingResponse(n).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/embeddings";
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    // 造响应：n 段文本 → n 条向量，第 i 条第一维 = i+1（可断言顺序与入参一致）
    private String embeddingResponse(int n) {
        StringBuilder sb = new StringBuilder("{\"data\":[");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"index\":").append(i)
                    .append(",\"embedding\":[").append(i + 1.0f).append(",0.0,0.0,0.0]}");
        }
        return sb.append("]}").toString();
    }

    // 批量 ≤ batchSize：一次请求就把多段文本全发出去（input 是数组，不是逐条打）
    @Test
    void batch_sendsAllTextsInOneRequest() {
        EmbeddingClient client = new EmbeddingClient("test-key", baseUrl);
        List<float[]> vecs = client.embedBatch(List.of("a", "b", "c"), 25);

        assertEquals(1, requestCount.get());            // 1 个请求搞定
        assertEquals(List.of(3), requestInputSizes);    // input 数组长度 = 3
        assertEquals(3, vecs.size());                   // 返回 3 条向量
        assertEquals(1.0f, vecs.get(0)[0]);             // 顺序与入参一致（第0条第一维=1）
        assertEquals(2.0f, vecs.get(1)[0]);
        assertEquals(3.0f, vecs.get(2)[0]);
    }

    // batchSize 封顶 25：30 段文本 → 2 批（25 + 5），不超限
    @Test
    void batch_cappedAt25() {
        EmbeddingClient client = new EmbeddingClient("test-key", baseUrl);
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 30; i++) texts.add("t" + i);

        List<float[]> vecs = client.embedBatch(texts, 25);

        assertEquals(2, requestCount.get());            // 分 2 批
        assertEquals(List.of(25, 5), requestInputSizes);// 批大小 25 和 5
        assertEquals(30, vecs.size());
    }

    // 单条 embed = 批量特例，复用同一链路
    @Test
    void embed_singleTextDelegatesToBatch() {
        EmbeddingClient client = new EmbeddingClient("test-key", baseUrl);
        float[] v = client.embed("hello");

        assertEquals(1, requestCount.get());
        assertEquals(List.of(1), requestInputSizes);
        assertEquals(1.0f, v[0]);
    }

    // 429 限流：前 2 个请求失败 → 自动重试，第 3 个成功
    @Test
    void retry_on429_thenSucceeds() {
        failTimes = 2;
        EmbeddingClient client = new EmbeddingClient("test-key", baseUrl);

        List<float[]> vecs = client.embedBatch(List.of("a", "b"), 25);

        assertEquals(3, requestCount.get());            // 2 失败 + 1 成功
        assertEquals(2, vecs.size());
        assertEquals(1.0f, vecs.get(0)[0]);
        assertEquals(2.0f, vecs.get(1)[0]);
    }

    // 一直 429：重试满 MAX_ATTEMPTS=3 次后抛异常，不无限打
    @Test
    void retry_exhaustsAfterMaxAttempts() {
        failTimes = 99;   // 永远 429
        EmbeddingClient client = new EmbeddingClient("test-key", baseUrl);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> client.embedBatch(List.of("a"), 25));

        assertTrue(ex.getMessage().contains("仍失败"));
        assertEquals(3, requestCount.get());            // 正好试满 3 次就停
    }

    // 非 429/5xx 的 4xx（如 400 参数错）：不可重试，一次就抛
    @Test
    void nonRetryable4xx_throwsImmediately() throws Exception {
        // 覆盖一次"永远 429"之外的路径：这里直接把 handler 换成一个回 400 的
        server.stop(0);
        server = null;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/embeddings", exchange -> {
                byte[] err = "{\"error\":\"bad request\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(400, err.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(err);
                }
            });
            server.start();
            baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/embeddings";

            EmbeddingClient client = new EmbeddingClient("test-key", baseUrl);
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> client.embedBatch(List.of("a"), 25));
            assertTrue(ex.getMessage().contains("400"));
        } finally {
            if (server != null) server.stop(0);
        }
    }
}
