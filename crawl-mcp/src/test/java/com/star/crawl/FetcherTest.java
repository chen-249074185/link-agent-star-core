package com.star.crawl;

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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Fetcher 测试：本地假 HTTP 服务器验证 UA 轮换 / 域名限速 / 指数退避
class FetcherTest {

    private HttpServer server;
    private String baseUrl;
    private final List<String> seenUAs = new ArrayList<>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicInteger failTimes = new AtomicInteger();   // 前 N 个请求回 500

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/page", exchange -> {
            seenUAs.add(exchange.getRequestHeaders().getFirst("User-Agent"));
            if (requestCount.incrementAndGet() <= failTimes.get()) {
                byte[] err = "server error".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, err.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(err);
                }
                return;
            }
            byte[] ok = "<html>ok</html>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(ok);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/page";
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    // ① UA 轮换：连续两次抓取 UA 不同
    @Test
    void uaRotatesBetweenRequests() {
        Fetcher fetcher = new Fetcher();
        fetcher.fetch(baseUrl);
        fetcher.fetch(baseUrl);

        assertEquals(2, seenUAs.size());
        assertNotEquals(seenUAs.get(0), seenUAs.get(1));   // 两次 UA 不一样
    }

    // ② 域名限速：同一域名两次抓取间隔 ≥300ms（第二次必须等）
    @Test
    void rateLimitsSameDomain() {
        Fetcher fetcher = new Fetcher();
        fetcher.fetch(baseUrl);

        long start = System.nanoTime();
        fetcher.fetch(baseUrl);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs >= 250, "第二次抓取应被限速等待，实际 " + elapsedMs + "ms");
    }

    // ③ 指数退避：前 2 次回 500 → 第 3 次成功（重试生效）
    @Test
    void retriesThenSucceeds() {
        failTimes.set(2);
        Fetcher fetcher = new Fetcher();

        String body = fetcher.fetch(baseUrl);

        assertEquals("<html>ok</html>", body);
        assertEquals(3, requestCount.get());   // 2 失败 + 1 成功
    }

    // ④ 一直 500：重试满 3 次抛异常，不无限打
    @Test
    void exhaustsAfterMaxAttempts() {
        failTimes.set(99);
        Fetcher fetcher = new Fetcher();

        RuntimeException ex = assertThrows(RuntimeException.class, () -> fetcher.fetch(baseUrl));
        assertTrue(ex.getMessage().contains("仍失败"));
        assertEquals(3, requestCount.get());
    }
}