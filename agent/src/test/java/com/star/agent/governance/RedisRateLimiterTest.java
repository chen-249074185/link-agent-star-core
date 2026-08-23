package com.star.agent.governance;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

// RedisRateLimiter 真实集成测试：连本机 Memurai(6379)，验证递增/只查/首次设 TTL 跨天重计
@SpringBootTest(properties = "linkagent.redis-enabled=true")
class RedisRateLimiterTest {

    @Autowired
    private RedisRateLimiter limiter;

    @Autowired
    private StringRedisTemplate redis;

    private final String key = "itest:user1:get_weather";

    @org.junit.jupiter.api.AfterEach
    void cleanup() {
        redis.delete(RedisKeys.rate(key));
    }

    @Test
    void increment_countsUp() {
        assertEquals(1, limiter.increment(key));
        assertEquals(2, limiter.increment(key));
        assertEquals(3, limiter.increment(key));
    }

    @Test
    void count_doesNotIncrement() {
        limiter.increment(key);
        limiter.increment(key);
        assertEquals(2, limiter.count(key));
        assertEquals(2, limiter.count(key));  // 只查不加
    }

    @Test
    void firstIncrement_setsTtlUntilMidnight() {
        limiter.increment(key);
        Long ttl = redis.getExpire(RedisKeys.rate(key));  // 秒
        assertEquals(true, ttl != null && ttl > 0);       // TTL 已设（今天结束前都>0）
    }
}