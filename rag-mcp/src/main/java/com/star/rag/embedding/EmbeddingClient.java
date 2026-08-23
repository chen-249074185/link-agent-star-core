package com.star.rag.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

// DashScope 文本向量客户端：调用 text-embedding-v3（OpenAI 兼容模式 /v1/embeddings）
public class EmbeddingClient {

    private static final String DEFAULT_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings";
    private static final String MODEL = "text-embedding-v3";

    private final String apiKey;
    private final String url;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public EmbeddingClient(String apiKey) {
        this(apiKey, DEFAULT_URL);
    }

    public EmbeddingClient(String apiKey, String url) {   // 测试可换假地址
        this.apiKey = apiKey;
        this.url = url;
    }

    // 单条文本 → 1024 维向量
    public float[] embed(String text) {
        try {
            Map<String, Object> body = Map.of(
                    "model", MODEL,
                    "input", text,
                    "dimensions", 1024);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("embedding 请求失败 status=" + resp.statusCode() + ": " + resp.body());
            }
            JsonNode data = mapper.readTree(resp.body()).path("data");
            JsonNode embedding = data.get(0).path("embedding");
            float[] vec = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vec[i] = (float) embedding.get(i).asDouble();
            }
            return vec;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("embedding 调用失败: " + e.getMessage(), e);
        }
    }

    // 批量（batch 上限 25；循环分批调用）
    public List<float[]> embedBatch(List<String> texts, int batchSize) {
        return texts.stream()
                .map(this::embed)
                .toList();
    }
}