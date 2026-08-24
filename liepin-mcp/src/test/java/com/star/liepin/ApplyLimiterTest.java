package com.star.liepin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// ApplyLimiter 测试：单日限次 / 跨天重置 / 剩余额度
class ApplyLimiterTest {

    @Test
    void limitsPerDay() {
        ApplyLimiter limiter = new ApplyLimiter(3);
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());          // 第 4 次超限
        assertEquals(0, limiter.remaining());
    }

    // 跨天自动重置（模拟跨天：同一天内先耗完，重置后恢复——用日期换日验证重置逻辑）
    @Test
    void resetsOnNewDay() {
        ApplyLimiter limiter = new ApplyLimiter(2);
        limiter.tryAcquire();
        limiter.tryAcquire();
        assertFalse(limiter.tryAcquire());

        // 直接改内部日期字段模拟"第二天"（包内可见，白盒验证跨天重置）
        limiter.day = "2099-01-01";
        assertTrue(limiter.tryAcquire());           // 新的一天额度恢复
        assertEquals(1, limiter.remaining());
    }
}