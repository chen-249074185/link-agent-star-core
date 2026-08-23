package com.star.agent.governance;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 反向验证：redis-enabled=false 时 Redis 版退役、内存版兜底
@SpringBootTest(properties = "linkagent.redis-enabled=false")
class RedisStoreDisabledTest {

    @Autowired
    private ApplicationContext ctx;

    @Test
    void inMemoryStoreRegisteredWhenDisabled() {
        assertFalse(ctx.containsBean("redisConfirmationStore"));
        assertFalse(ctx.containsBean("redisRateLimiter"));
        assertTrue(ctx.containsBean("inMemoryConfirmationStore"));
        assertTrue(ctx.containsBean("inMemoryRateLimiter"));
    }
}