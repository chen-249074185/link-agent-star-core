package com.star.agent.governance;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Duration;

// 限次：Redis 版实现（正式版），key 走 RedisKeys 规范
// 跨天清零靠 TTL：第一次计数时把 key 的过期时间设到"明天零点"，到点 Redis 自动删，天然重计
@Component
@ConditionalOnProperty(name = "linkagent.redis-enabled", havingValue = "true")
public class RedisRateLimiter implements RateLimiter {

    private final StringRedisTemplate redis;

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public int increment(String key) {
        // INCR 原子自增：并发下也不会加错（比内存版 Map 更可靠）
        Long count = redis.opsForValue().increment(RedisKeys.rate(key));
        if (count != null && count == 1) {
            // 首次计数：设 TTL 到今天结束（明天 00:00:00），跨天自动清零
            LocalDateTime midnight = LocalDate.now().plusDays(1).atStartOfDay();
            long ttlSeconds = Duration.between(LocalDateTime.now(), midnight).getSeconds();
            redis.expire(RedisKeys.rate(key), Duration.ofSeconds(ttlSeconds));
        }
        return count == null ? 0 : count.intValue();
    }

    @Override
    public int count(String key) {
        // 只查不增：过期 key 已消失，返回 0
        String v = redis.opsForValue().get(RedisKeys.rate(key));
        return v == null ? 0 : Integer.parseInt(v);
    }
}