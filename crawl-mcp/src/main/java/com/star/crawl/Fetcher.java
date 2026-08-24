package com.star.crawl;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// 网页抓取器：UA 池轮换（反反爬）+ 域名限速 300ms（礼貌爬取）+ 指数退避重试（健壮性）
// 思路借鉴夏令营项目 HtmlFetcher 管线（附 C：只搬精华不搬巨型类），自研实现
public class Fetcher {

    // ① UA 池：3 个真实浏览器 UA，轮流伪装成不同浏览器（同 UA 高频请求是反爬最强判定信号）
    private static final List<String> UA_POOL = List.of(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:127.0) Gecko/20100101 Firefox/127.0",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15"
    );

    private static final long DOMAIN_MIN_INTERVAL_MS = 300;   // ② 同一域名两次请求最少间隔（约 3 次/秒）
    private static final long MAX_ATTEMPTS = 3;               // ③ 最多尝试次数（含第一次）
    private static final long REQUEST_TIMEOUT_SECONDS = 10;   // 单请求超时：防死等

    private final AtomicInteger uaCounter = new AtomicInteger();                    // UA 轮流计数（线程安全）
    private final Map<String, Long> lastFetchByDomain = new ConcurrentHashMap<>(); // 域名 → 上次抓取时间戳
    private final HttpClient http = HttpClient.newHttpClient();

    // 对外唯一方法：拿网页内容（完整：限速 → 轮换 UA 请求 → 失败退避）
    public String fetch(String url) {
        String domain = URI.create(url).getHost();   // 域名（限速按域名记，同站各页共享额度）
        throttle(domain);                            // ① 域名限速

        for (int attempt = 1; ; attempt++) {         // ② 退避重试循环
            try {
                String ua = UA_POOL.get(uaCounter.getAndIncrement() % UA_POOL.size());   // UA 轮换
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", ua)
                        .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                        .GET()
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    return resp.body();              // 成功：直接返回
                }
                if (!isRetryable(resp.statusCode())) {
                    // 404 等必错状态：重试也没用，直接抛
                    throw new RuntimeException("抓取失败 status=" + resp.statusCode() + " url=" + url);
                }
                // 403/429/5xx → 可重试，落到循环底部统一判断次数
            } catch (IOException | InterruptedException e) {
                // 网络错/超时 → 可重试，落到循环底部
            }
            if (attempt >= MAX_ATTEMPTS) {
                throw new RuntimeException("抓取重试 " + MAX_ATTEMPTS + " 次仍失败 url=" + url);
            }
            backoff(attempt);                        // ③ 指数退避
        }
    }

    // 域名限速：距上次抓取不足 300ms 就睡到够；第一次抓该域名直接放行
    private void throttle(String domain) {
        long now = System.currentTimeMillis();
        Long last = lastFetchByDomain.get(domain);
        if (last != null) {
            long wait = DOMAIN_MIN_INTERVAL_MS - (now - last);
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();   // 恢复中断标记（不吞掉）
                }
            }
        }
        lastFetchByDomain.put(domain, System.currentTimeMillis());   // 睡完才记时间，限速才准
    }

    // 值得重试的状态码：429 限流 / 5xx 服务端错 / 403 反爬标记（403 换 UA 碰运气）
    private boolean isRetryable(int status) {
        return status == 403 || status == 429 || status >= 500;
    }

    // 指数退避：第 1 次失败等 500ms，第 2 次等 1000ms（越退越久，别连打刺激限流）
    private void backoff(int attempt) {
        try {
            Thread.sleep(500L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}