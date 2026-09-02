package com.star.rag.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

// DashScope 文本向量客户端：调用 text-embedding-v3（OpenAI 兼容模式 /v1/embeddings）
public class EmbeddingClient {

    private static final String DEFAULT_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings";
    private static final String MODEL = "text-embedding-v3";
    private static final int MAX_BATCH = 10;      // DashScope text-embedding-v3 单次请求 batch 上限（实测 400: should not be larger than 10）
    private static final int MAX_ATTEMPTS = 3;    // 每批发失败最多尝试次数
    private static final int PARALLELISM = 4;     // 批次并发数（11-02 上传慢优化：39 批串行 ~60s → 4 并发 ~15s）

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

    // 单条文本 → 1024 维向量（批量的一种特例，复用同一套请求+重试逻辑）
    public float[] embed(String text) {
        return embedBatch(List.of(text), 1).get(0);
    }

    // 批量向量化：每批 ≤ batchSize（超 MAX_BATCH 压到 MAX_BATCH）。
    // 多批并发提交（固定 4 线程），结果按批次顺序归位——返回顺序与入参一致，且总耗时 ≈ 单批 × (批数/并发)
    public List<float[]> embedBatch(List<String> texts, int batchSize) {
        int batch = batchSize <= 0 ? MAX_BATCH : Math.min(batchSize, MAX_BATCH);
        List<List<String>> batches = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += batch) {
            batches.add(texts.subList(i, Math.min(i + batch, texts.size())));
        }
        if (batches.isEmpty()) {
            return List.of();
        }
        if (batches.size() == 1) {
            return embedBatchOnce(batches.get(0));   // 单批无并发必要，快路径
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(PARALLELISM, batches.size()));
        try {
            List<Future<List<float[]>>> futures = batches.stream()
                    .map(b -> pool.submit(() -> embedBatchOnce(b)))
                    .toList();
            List<float[]> all = new ArrayList<>();
            for (Future<List<float[]>> f : futures) {
                all.addAll(f.get());   // 按批次索引顺序归位：任一批失败 → 抛异常中断
            }
            return all;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("embedding 批量向量化被中断", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("embedding 批量向量化失败: " + e.getCause().getMessage(), e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    // 发一个批量请求（input 传数组）：200 直接返回；429/5xx/网络错 → 指数退避重试，超 MAX_ATTEMPTS 抛异常
    private List<float[]> embedBatchOnce(List<String> batch) {
        for (int attempt = 1; ; attempt++) {
            try {
                Map<String, Object> body = Map.of(
                        "model", MODEL,
                        "input", batch,          // 数组 → 一次请求向量化多段文本
                        "dimensions", 1024);
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    return parseEmbeddings(resp.body());
                }
                if (!isRetryable(resp.statusCode())) {
                    // 非 429/5xx 的 4xx（如 400 参数错/401 key 错）→ 重试也没用，直接抛
                    throw new RuntimeException("embedding 请求失败 status=" + resp.statusCode() + ": " + resp.body());
                }
            } catch (IOException | InterruptedException e) {
                // 网络错/中断 → 可重试，落到下面统一判断
            } catch (Exception e) {
                // JSON 序列化/解析错属于代码问题，不可重试
                throw new RuntimeException("embedding 请求/解析失败: " + e.getMessage(), e);
            }
            if (attempt >= MAX_ATTEMPTS) {
                throw new RuntimeException("embedding 批量请求 " + MAX_ATTEMPTS + " 次尝试后仍失败");
            }
            backoff(attempt);
        }
    }

    // 429 限流 / 5xx 服务端错误 → 值得重试
    private boolean isRetryable(int status) {
        return status == 429 || status >= 500;
    }

    // 指数退避：第 1 次重试等 500ms，第 2 次等 1000ms（避免连打撞限流）
    private void backoff(int attempt) {
        try {
            Thread.sleep(500L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // 解析响应 data[] → 向量列表（接口按输入顺序返回，逐条转 float[]）
    private List<float[]> parseEmbeddings(String body) throws Exception {
        JsonNode data = mapper.readTree(body).path("data");
        List<float[]> vecs = new ArrayList<>();
        for (JsonNode item : data) {
            JsonNode emb = item.path("embedding");
            float[] vec = new float[emb.size()];
            for (int i = 0; i < emb.size(); i++) {
                vec[i] = (float) emb.get(i).asDouble();
            }
            vecs.add(vec);
        }
        return vecs;
    }
}