package com.star.agent.governance;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 正向验证：redis-enabled=true 时 Redis 版注册、内存版退役（治理存储换 Redis）
@SpringBootTest(properties = "linkagent.redis-enabled=true")
class RedisStoreEnabledTest {

    @Autowired
    private ApplicationContext ctx;

    @Test
    void redisStoreRegisteredWhenEnabled() {
        assertTrue(ctx.containsBean("redisConfirmationStore"));
        assertTrue(ctx.containsBean("redisRateLimiter"));
        assertFalse(ctx.containsBean("inMemoryConfirmationStore"));
        assertFalse(ctx.containsBean("inMemoryRateLimiter"));
    }
}