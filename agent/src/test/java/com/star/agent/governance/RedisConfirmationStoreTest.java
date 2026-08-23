package com.star.agent.governance;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// RedisConfirmationStore 真实集成测试：连本机 Memurai(6379)，验证存取/过期/删除
@SpringBootTest(properties = "linkagent.redis-enabled=true")
class RedisConfirmationStoreTest {

    @Autowired
    private RedisConfirmationStore store;

    @Autowired
    private StringRedisTemplate redis;

    private final String key = "itest:user1:liepin_apply";

    // 每个测试后清理，避免污染真实 key
    @org.junit.jupiter.api.AfterEach
    void cleanup() {
        redis.delete(RedisKeys.confirmation(key));
    }

    @Test
    void saveThenGet_returnsValue() {
        store.save(key, "yes", 300);
        assertEquals("yes", store.get(key));
    }

    @Test
    void missingKey_returnsNull() {
        assertNull(store.get(key));
    }

    @Test
    void delete_removesValue() {
        store.save(key, "yes", 300);
        store.delete(key);
        assertNull(store.get(key));
    }

    @Test
    void ttl_expiresValue() throws InterruptedException {
        store.save(key, "yes", 1);  // 1 秒后过期
        Thread.sleep(1100);         // Redis 过期不是瞬时的，稍等再查
        assertNull(store.get(key));
    }
}