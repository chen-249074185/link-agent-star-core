package com.star.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.star.agent.llm.LlmClient;
import com.star.agent.llm.ChatResponse;
import com.star.agent.governance.RedisKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// EphemeralMemory 真实 Redis 集成测试：连本机 Memurai(6379)，验证存取/TTL/追加压缩
@SpringBootTest(properties = "linkagent.redis-enabled=true")
class EphemeralMemoryTest {

    @Autowired
    private StringRedisTemplate redis;

    @AfterEach
    void cleanup() {
        redis.delete(RedisKeys.memory("itest:user1"));
        redis.delete(RedisKeys.memory("itest:user2"));
    }

    @Test
    void saveThenLoad_roundTrip() {
        LlmClient llm = mock(LlmClient.class);
        EphemeralMemory memory = new EphemeralMemory(redis, new ObjectMapper(), llm);

        memory.save("itest:user1", List.of(
                new ChatEntry("user", "你好"),
                new ChatEntry("assistant", "你好呀")
        ));

        List<ChatEntry> loaded = memory.load("itest:user1");
        assertEquals(2, loaded.size());
        assertEquals("user", loaded.get(0).role());
        assertEquals("你好", loaded.get(0).content());
    }

    @Test
    void missingKey_returnsEmptyList() {
        LlmClient llm = mock(LlmClient.class);
        EphemeralMemory memory = new EphemeralMemory(redis, new ObjectMapper(), llm);
        assertEquals(0, memory.load("itest:user1").size());
    }

    // 用户间记忆隔离（两账号不串音的记忆层证据）：u1 存的东西 u2 读不到
    @Test
    void userIsolation_otherUserCannotRead() {
        LlmClient llm = mock(LlmClient.class);
        EphemeralMemory memory = new EphemeralMemory(redis, new ObjectMapper(), llm);

        memory.save("itest:user1", List.of(new ChatEntry("user", "一号的私密对话")));

        assertEquals(1, memory.load("itest:user1").size(), "本人应能读到自己的记忆");
        assertEquals(0, memory.load("itest:user2").size(), "他人读不到我的记忆（Redis key 按 userId 隔离）");
    }

    @Test
    void save_setsTtl30Minutes() {
        LlmClient llm = mock(LlmClient.class);
        EphemeralMemory memory = new EphemeralMemory(redis, new ObjectMapper(), llm);
        memory.save("itest:user1", List.of(new ChatEntry("user", "hi")));

        Long ttl = redis.getExpire(RedisKeys.memory("itest:user1"));
        assertTrue(ttl != null && ttl > 1700 && ttl <= 1800);  // 约 30 分钟
    }

    @Test
    void append_thenCompressWhenOverLimit() {
        LlmClient llm = mock(LlmClient.class);
        when(llm.chat(anyList(), anyList())).thenReturn(new ChatResponse("摘要", List.of()));
        EphemeralMemory memory = new EphemeralMemory(redis, new ObjectMapper(), llm);

        // 追加 11 轮（22 条）触发压缩
        for (int i = 1; i <= 11; i++) {
            memory.append("itest:user1", "问" + i, "答" + i);
        }

        List<ChatEntry> loaded = memory.load("itest:user1");
        assertEquals(11, loaded.size());   // 1 条摘要 + 最近 10 条
        assertEquals("system", loaded.get(0).role());
    }
}