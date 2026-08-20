package com.star.agent.governance;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// 确认态存储：内存版实现（临时），W6 换 Redis 版
@Component
public class InMemoryConfirmationStore implements ConfirmationStore {

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    // 一条确认记录：内容 + 过期时间戳
    private record Entry(String value, long expireAt) {}

    @Override
    public void save(String key, String value, long ttlSeconds) {
        store.put(key, new Entry(value, System.currentTimeMillis() + ttlSeconds * 1000));
    }

    @Override
    public String get(String key) {
        Entry e = store.get(key);
        if (e == null) return null;
        if (System.currentTimeMillis() > e.expireAt()) {  // 过期即视为未确认
            store.remove(key);
            return null;
        }
        return e.value();
    }

    @Override
    public void delete(String key) {
        store.remove(key);
    }
}