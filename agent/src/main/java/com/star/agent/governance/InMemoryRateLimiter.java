package com.star.agent.governance;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// 限次：内存版实现（临时兜底），按"日期+key"分组计数，linkagent.redis-enabled=false 时才注册
@Component
@ConditionalOnProperty(name = "linkagent.redis-enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryRateLimiter implements RateLimiter {

    private final Map<String, Counter> store = new ConcurrentHashMap<>();

    // 一条计数：次数 + 它属于哪一天
    private record Counter(int count, String day) {}

    @Override
    public int increment(String key) {
        String today = LocalDate.now().toString();
        Counter c = store.get(key);
        if (c == null || !c.day().equals(today)) {
            store.put(key, new Counter(1, today));
            return 1;
        }
        store.put(key, new Counter(c.count() + 1, today));
        return c.count() + 1;
    }

    @Override
    public int count(String key) {
        Counter c = store.get(key);
        if (c == null) return 0;
        String today = LocalDate.now().toString();
        return c.day().equals(today) ? c.count() : 0;
    }
}