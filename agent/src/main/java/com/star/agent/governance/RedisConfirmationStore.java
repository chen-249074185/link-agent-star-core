package com.star.agent.governance;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

// 确认态存储：Redis 版实现（正式版），key 走 RedisKeys 规范
// 条件装配：linkagent.redis-enabled=true 时注册，取代内存版
@Component
@ConditionalOnProperty(name = "linkagent.redis-enabled", havingValue = "true")
public class RedisConfirmationStore implements ConfirmationStore {

    private final StringRedisTemplate redis;

    // StringRedisTemplate 是 Spring Boot 自动配好的 bean，存字符串正好够用
    public RedisConfirmationStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void save(String key, String value, long ttlSeconds) {
        // TTL 交给 Redis 原生过期（不用像内存版那样自己算过期时间戳）
        redis.opsForValue().set(RedisKeys.confirmation(key), value, Duration.ofSeconds(ttlSeconds));
    }

    @Override
    public String get(String key) {
        // Redis 过期后自动消失，get 自然返回 null，无需懒过期逻辑
        return redis.opsForValue().get(RedisKeys.confirmation(key));
    }

    @Override
    public void delete(String key) {
        redis.delete(RedisKeys.confirmation(key));
    }
}