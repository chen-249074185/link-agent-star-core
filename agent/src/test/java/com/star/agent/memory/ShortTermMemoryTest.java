package com.star.agent.memory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// ShortTermMemory 真实 MySQL 集成测试：连本机 linkagent 库，验证异步落库 + 查历史
// 注意：saveAsync 是异步并发落库，写入顺序不可控，断言按"内容集合"而非"顺序"
@SpringBootTest(properties = "linkagent.redis-enabled=true")
class ShortTermMemoryTest {

    @Autowired
    private ShortTermMemory memory;

    @Autowired
    private ConversationMapper mapper;

    private final String userId = "itest:user" + System.currentTimeMillis();  // 唯一用户，避免脏数据

    @org.junit.jupiter.api.AfterEach
    void cleanup() {
        mapper.deleteByUserId(userId);
    }

    // 轮询等待异步落库完成（最多 3 秒），避免 sleep 定死时间
    private void awaitCount(int expected) throws InterruptedException {
        for (int i = 0; i < 30; i++) {
            if (mapper.findByUserId(userId, 50).size() >= expected) return;
            Thread.sleep(100);
        }
    }

    @Test
    void saveAsync_persistsBothSides() throws InterruptedException {
        memory.saveAsync(userId, "user", "我想在杭州租房");
        memory.saveAsync(userId, "assistant", "好的，杭州哪个区？");
        awaitCount(2);

        List<Conversation> history = memory.findHistory(userId, 10);
        assertEquals(2, history.size());
        // 两条都落库：user 一条 + assistant 一条（顺序不定，按集合断言）
        Set<String> roles = history.stream().map(Conversation::getRole).collect(Collectors.toSet());
        assertEquals(Set.of("user", "assistant"), roles);
        Set<String> contents = history.stream().map(Conversation::getContent).collect(Collectors.toSet());
        assertEquals(Set.of("我想在杭州租房", "好的，杭州哪个区？"), contents);
    }

    @Test
    void findHistory_limitWorks() throws InterruptedException {
        for (int i = 1; i <= 5; i++) {
            memory.saveAsync(userId, "user", "问题" + i);
        }
        awaitCount(5);

        List<Conversation> history = memory.findHistory(userId, 3);
        assertEquals(3, history.size());   // LIMIT 生效：5 条里只取 3 条
        // 取出的 3 条都来自这 5 条（异步并发写入顺序不定，不断言具体哪 3 条）
        Set<String> contents = history.stream().map(Conversation::getContent).collect(Collectors.toSet());
        assertTrue(contents.stream().allMatch(c -> c.startsWith("问题")));
        assertEquals(3, contents.size());
        assertTrue(history.stream().allMatch(c -> c.getCreatedAt() != null));  // 时间自动填上
    }

    @Test
    void findHistory_unknownUser_returnsEmpty() {
        List<Conversation> history = memory.findHistory("no-such-user", 10);
        assertEquals(0, history.size());
    }
}