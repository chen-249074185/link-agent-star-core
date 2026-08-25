package com.star.agent.adapter;

import com.star.agent.message.AgentMessage;
import com.star.agent.message.MediaType;
import com.star.agent.message.MessageSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// DebounceProcessor 单测：窗口两档/聚合内容/立即触发/多用户隔离（now 参数化，不必真等）
class DebounceProcessorTest {

    private final DebounceProcessor dp = new DebounceProcessor();
    private long now = 1_000_000L;   // 假时钟：每次操作手动推进

    private AgentMessage text(String id, String text) {
        return new AgentMessage(MessageSource.WECHAT, "u1", text, MediaType.TEXT, null, id, Instant.ofEpochMilli(now));
    }

    private AgentMessage image(String id) {
        return new AgentMessage(MessageSource.WECHAT, "u1", "", MediaType.IMAGE, new byte[]{1}, id, Instant.ofEpochMilli(now));
    }

    // 纯文字 1.5s 窗口：到期才产出，未到期不产
    @Test
    void textWindow_1500ms() {
        assertNull(dp.add("u1", text("m1", "看看这个"), now));
        assertTrue(dp.checkDue(now + 1499).isEmpty(), "1.5s 内不产出");
        List<PendingMessage> due = dp.checkDue(now + 1500);
        assertEquals(1, due.size(), "1.5s 到点产出");
        assertEquals("看看这个", due.get(0).text());
        assertFalse(dp.hasPending("u1"), "产出后桶清空");
    }

    // 图片 3s 窗口：1.5s 时文字窗口已过但图片仍在攒（用户可能补文字说明）
    @Test
    void imageWindow_3000ms() {
        assertNull(dp.add("u1", image("m1"), now));
        assertTrue(dp.checkDue(now + 1500).isEmpty(), "图片窗口 3s：1.5s 时还不产");
        List<PendingMessage> due = dp.checkDue(now + 3000);
        assertEquals(1, due.size(), "3s 到点产出");
        assertTrue(due.get(0).hasImage());
    }

    // 聚合：图 + 文字说明 → 一个包，文本拼合、含图标记、消息段保序
    @Test
    void mixedImageAndText_aggregated() {
        assertNull(dp.add("u1", image("m1"), now));
        assertNull(dp.add("u1", text("m2", "看看这个房子有什么问题"), now + 1000));   // 1s 后补文字 → 重计窗口

        List<PendingMessage> due = dp.checkDue(now + 1000 + 3000);   // 从最后一条起 3s

        assertEquals(1, due.size(), "图+文字应合成一个包");
        PendingMessage pm = due.get(0);
        assertEquals(2, pm.messages().size(), "两条消息都在包里");
        assertEquals("看看这个房子有什么问题", pm.text());
        assertTrue(pm.hasImage());
    }

    // 碎片合并：连续两条文字 1.5s 内到达 → 只产出一次，文本拼合
    @Test
    void fragmentedTexts_merged() {
        assertNull(dp.add("u1", text("m1", "第一句"), now));
        assertNull(dp.add("u1", text("m2", "第二句"), now + 800));   // 0.8s 后第二条 → 窗口重计

        List<PendingMessage> due = dp.checkDue(now + 800 + 1500);

        assertEquals(1, due.size());
        assertEquals("第一句\n第二句", due.get(0).text());
    }

    // 立即触发：文本含"直接处理" → 不等窗口立刻聚合（含新消息和之前攒的）
    @Test
    void triggerWord_flushesImmediately() {
        assertNull(dp.add("u1", image("m1"), now));
        assertNull(dp.add("u1", text("m2", "看下"), now + 500));

        PendingMessage pm = dp.add("u1", text("m3", "直接处理"), now + 700);

        assertEquals(3, pm.messages().size(), "立即聚合含全部消息");
        assertFalse(dp.hasPending("u1"), "触发后桶清空");
    }

    // 立即触发："否" 单字同样生效
    @Test
    void triggerNo_flushesImmediately() {
        assertNull(dp.add("u1", text("m1", "修复这张图"), now));

        PendingMessage pm = dp.add("u1", text("m2", "否"), now + 500);

        assertEquals(2, pm.messages().size());
    }

    // 多用户隔离：u1 在攒不影响 u2
    @Test
    void users_isolated() {
        assertNull(dp.add("u1", text("m1", "u1的消息"), now));
        assertNull(dp.add("u2", text("m2", "u2的消息"), now));

        assertTrue(dp.hasPending("u1"));
        assertTrue(dp.hasPending("u2"));

        List<PendingMessage> due = dp.checkDue(now + 1500);
        assertEquals(2, due.size(), "两个用户各产出一个包");
        assertEquals("u1的消息", due.get(0).text());
        assertEquals("u2的消息", due.get(1).text());
    }

    // 空桶 flush：没有在攒的用户返回 null
    @Test
    void flushNoPending_returnsNull() {
        assertTrue(dp.checkDue(now).isEmpty());
    }
}