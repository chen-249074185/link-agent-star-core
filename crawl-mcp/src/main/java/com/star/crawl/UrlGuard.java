package com.star.crawl;

import java.net.URI;

// SSRF 防护（安全边界）：爬虫是"服务器帮你抓网页"，攻击者可能诱导抓内网地址
// （localhost/127.0.0.1/内网段/云元数据地址）→ 把服务器当跳板探测内网。抓取前必须校验。
public class UrlGuard {

    // 明确禁止的主机名/地址（含云厂商元数据端点）
    private static final java.util.Set<String> BLOCKED_HOSTS = java.util.Set.of(
            "localhost", "127.0.0.1", "0.0.0.0", "::1",
            "metadata.google.internal", "169.254.169.254");   // 169.254.169.254 = 云元数据服务

    // 校验 URL：内网/保留地址抛 IllegalArgumentException，否则通过
    public static void check(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL 不能为空");
        }
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (Exception e) {
            throw new IllegalArgumentException("URL 非法: " + url);
        }
        if (host == null || isPrivateHost(host.toLowerCase())) {
            throw new IllegalArgumentException("禁止抓取内网/保留地址: " + url);
        }
    }

    private static boolean isPrivateHost(String h) {
        if (BLOCKED_HOSTS.contains(h)) {
            return true;
        }
        // 常见内网段：10.x / 192.168.x / 172.16~31.x
        if (h.startsWith("10.") || h.startsWith("192.168.")) {
            return true;
        }
        for (int i = 16; i <= 31; i++) {
            if (h.startsWith("172." + i + ".")) {
                return true;
            }
        }
        return false;
    }
}