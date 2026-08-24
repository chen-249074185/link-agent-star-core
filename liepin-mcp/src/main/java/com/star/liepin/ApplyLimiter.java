package com.star.liepin;

import java.time.LocalDate;

// 投递单日限次（20 次/天，计划要求）：跨天自动重置（懒过期思路，和治理内核 RateLimiter 同款）
public class ApplyLimiter {

    private final int maxPerDay;
    String day = "";      // 包可见：同包测试白盒验证跨天重置
    private int count = 0;

    public ApplyLimiter(int maxPerDay) {
        this.maxPerDay = maxPerDay;
    }

    // 尝试占用一次额度：成功返回 true；当日超限返回 false
    public synchronized boolean tryAcquire() {
        String today = LocalDate.now().toString();
        if (!day.equals(today)) {
            day = today;        // 跨天：重置计数
            count = 0;
        }
        if (count >= maxPerDay) {
            return false;
        }
        count++;
        return true;
    }

    // 今日剩余额度（跨天也自动重置）
    public synchronized int remaining() {
        String today = LocalDate.now().toString();
        if (!day.equals(today)) {
            day = today;
            count = 0;
        }
        return Math.max(0, maxPerDay - count);
    }
}