package com.star.agent.adapter.wechat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// BotRegistry 多 Bot 注册表单测：登记启停/清点/顶号/批量停
// 用假 worker（覆写 run/stop）避免真连微信；构造参数传 null 即可（假 worker 不用它们）
class BotRegistryTest {

    // 假 worker：run 里 countDown 证明线程真的起来了，stop 置标记——只验证注册表的登记/注销行为
    private static class FakeWorker extends BotWorker {
        final java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        volatile boolean stopped = false;

        FakeWorker(String botId) {
            super(botId, null, null, null, null, null, null, null, null, null);
        }

        @Override
        public void run() {
            started.countDown();
        }

        @Override
        public void stop() {
            stopped = true;
        }
    }

    @Test
    void start_registersAndStartsThread() throws Exception {
        BotRegistry registry = new BotRegistry();
        FakeWorker worker = new FakeWorker("bot-a");

        registry.start("bot-a", worker);

        assertTrue(worker.started.await(1, java.util.concurrent.TimeUnit.SECONDS), "登记后线程应立即跑起来");
        assertTrue(registry.isRunning("bot-a"));
        assertEquals(1, registry.size());
        assertEquals(List.of("bot-a"), registry.botIds());
        assertFalse(worker.stopped, "新登记的 bot 不应被停");
    }

    // 同 botId 重复登记 = 顶号：旧实例被停、新实例接管
    @Test
    void start_duplicateBotId_stopsPrevious() {
        BotRegistry registry = new BotRegistry();
        FakeWorker oldWorker = new FakeWorker("bot-a");
        FakeWorker newWorker = new FakeWorker("bot-a");

        registry.start("bot-a", oldWorker);
        registry.start("bot-a", newWorker);

        assertTrue(oldWorker.stopped, "顶号时旧实例应被停止");
        assertEquals(1, registry.size(), "同 botId 只留一个");
        assertTrue(registry.isRunning("bot-a"));
    }

    @Test
    void stop_removesAndStops() {
        BotRegistry registry = new BotRegistry();
        FakeWorker worker = new FakeWorker("bot-a");
        registry.start("bot-a", worker);

        registry.stop("bot-a");

        assertTrue(worker.stopped);
        assertFalse(registry.isRunning("bot-a"));
        assertEquals(0, registry.size());
    }

    // 停不存在的 bot 是零副作用（登出命令对不上号时安全）
    @Test
    void stop_unknownBot_noop() {
        BotRegistry registry = new BotRegistry();

        registry.stop("no-such-bot");

        assertEquals(0, registry.size());
    }

    // 多 bot 并行登记：互不干扰（两账号不串音的前提）
    @Test
    void start_multipleBots_isolated() {
        BotRegistry registry = new BotRegistry();
        FakeWorker a = new FakeWorker("bot-a");
        FakeWorker b = new FakeWorker("bot-b");

        registry.start("bot-a", a);
        registry.start("bot-b", b);

        assertEquals(2, registry.size());
        assertTrue(registry.isRunning("bot-a"));
        assertTrue(registry.isRunning("bot-b"));
        assertEquals(List.of("bot-a", "bot-b"), registry.botIds());
    }

    // 应用关闭：stopAll 全部停掉并清空
    @Test
    void stopAll_stopsEveryBot() {
        BotRegistry registry = new BotRegistry();
        FakeWorker a = new FakeWorker("bot-a");
        FakeWorker b = new FakeWorker("bot-b");
        registry.start("bot-a", a);
        registry.start("bot-b", b);

        registry.stopAll();

        assertTrue(a.stopped);
        assertTrue(b.stopped);
        assertEquals(0, registry.size());
        assertFalse(registry.isRunning("bot-a"));
    }
}