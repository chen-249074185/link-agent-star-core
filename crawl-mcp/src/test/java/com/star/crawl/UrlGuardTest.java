package com.star.crawl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

// UrlGuard 测试：SSRF 防护（内网/保留地址拒绝，公网放行）
class UrlGuardTest {

    // 内网/保留地址全部拦截
    @Test
    void blocksPrivateHosts() {
        assertThrows(IllegalArgumentException.class, () -> UrlGuard.check("http://localhost:8080/admin"));
        assertThrows(IllegalArgumentException.class, () -> UrlGuard.check("http://127.0.0.1/secret"));
        assertThrows(IllegalArgumentException.class, () -> UrlGuard.check("http://0.0.0.0/x"));
        assertThrows(IllegalArgumentException.class, () -> UrlGuard.check("http://10.0.0.1/x"));
        assertThrows(IllegalArgumentException.class, () -> UrlGuard.check("http://192.168.1.1/x"));
        assertThrows(IllegalArgumentException.class, () -> UrlGuard.check("http://172.16.0.1/x"));
        assertThrows(IllegalArgumentException.class, () -> UrlGuard.check("http://172.31.255.255/x"));
        // 云元数据服务地址（AWS 等，抓了能拿到云凭证）
        assertThrows(IllegalArgumentException.class, () -> UrlGuard.check("http://169.254.169.254/latest/meta-data"));
    }

    // 公网地址放行
    @Test
    void allowsPublicHosts() {
        assertDoesNotThrow(() -> UrlGuard.check("https://www.liepin.com/"));
        assertDoesNotThrow(() -> UrlGuard.check("https://restapi.amap.com/v3/direction/driving"));
    }

    // 非法/空 URL 拒绝
    @Test
    void rejectsInvalidUrls() {
        assertThrows(IllegalArgumentException.class, () -> UrlGuard.check(""));
        assertThrows(IllegalArgumentException.class, () -> UrlGuard.check(null));
        assertThrows(IllegalArgumentException.class, () -> UrlGuard.check("not-a-url"));
    }
}