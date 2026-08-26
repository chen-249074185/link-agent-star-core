package com.star.agent.adapter;

import com.star.agent.message.AgentMessage;
import com.star.agent.message.MediaType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// 消息防抖聚合器：
// 用户连续发消息时先攒着不处理，静默期（窗口）过了没有新消息才打包产出——防止
// ① AI 回复穿插在用户连续消息中间 ② 图+文字说明被拆散/顺序颠倒（文字先到会被下游忽略）
// ③ 碎片消息多次调 AI 浪费 token
//
// 窗口：图片/语音/文件 3s（用户可能还要补文字说明），纯文字 1.5s（一般不用等太久）
// 立即触发：文本含"直接处理"或等于"否"→ 不等窗口立刻聚合（用户明说够了）
// 调度：纯逻辑 + 外部 tick（checkDue(now) 由调用方周期调，如 processor 200ms 轮询），测试可控
@Component
public class DebounceProcessor {

    public static final long TEXT_WINDOW_MS = 1500;        // 纯文字 1.5s
    public static final long MEDIA_WINDOW_MS = 3000;       // 图片/语音/文件 3s
    private static final String TRIGGER_DIRECT = "直接处理";   // 立即触发词
    private static final String TRIGGER_NO = "否";             // 立即触发词（用户明说不用再等）

    // 攒消息桶：userId → 桶（消息段 + 静默期截止时刻）
    private static final class Bucket {
        final List<AgentMessage> messages = new ArrayList<>();
        long deadlineAt;   // 超过该时刻（含）即视为静默期结束
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    // 收一条消息：命中触发词 → 立即聚合产出；否则入桶等静默期，返回 null
    public PendingMessage add(String userId, AgentMessage msg, long now) {
        String text = msg.text() == null ? "" : msg.text().trim();
        if (text.contains(TRIGGER_DIRECT) || TRIGGER_NO.equals(text)) {
            Bucket b = buckets.get(userId);
            if (b != null) {
                b.messages.add(msg);
                return flush(userId, now);   // 立即聚合：把新消息和之前攒的一起交出去
            }
        }
        Bucket b = buckets.computeIfAbsent(userId, k -> new Bucket());
        b.messages.add(msg);
        b.deadlineAt = now + windowMs(msg.mediaType());
        return null;   // 还没齐：等静默期结束或触发词
    }

    // 周期检查：静默期到期的桶全部聚合产出（调用方轮询调，如 processor 200ms）
    public List<PendingMessage> checkDue(long now) {
        List<PendingMessage> out = new ArrayList<>();
        for (String userId : new java.util.ArrayList<>(buckets.keySet())) {
            Bucket b = buckets.get(userId);
            if (b != null && now >= b.deadlineAt) {
                PendingMessage pm = build(userId, b);
                buckets.remove(userId);
                out.add(pm);
            }
        }
        return out;
    }

    // 该用户当前是否在攒消息（防抖窗口内）
    public boolean hasPending(String userId) {
        return buckets.containsKey(userId);
    }

    // 按消息类型定窗口：图片/语音/文件 3s（可能要补说明），纯文字 1.5s
    private long windowMs(MediaType mediaType) {
        return mediaType == MediaType.TEXT ? TEXT_WINDOW_MS : MEDIA_WINDOW_MS;
    }

    // 强制立即聚合（触发词用）：把当前桶 + 新消息打包产出
    private PendingMessage flush(String userId, long now) {
        Bucket b = buckets.remove(userId);
        if (b == null) {
            return null;
        }
        return build(userId, b);
    }

    // 桶 → PendingMessage：拼合文本 + 标记是否含图
    private PendingMessage build(String userId, Bucket b) {
        StringBuilder sb = new StringBuilder();
        boolean hasImage = false;
        for (AgentMessage m : b.messages) {
            if (m.text() != null && !m.text().isEmpty()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(m.text());
            }
            if (m.mediaType() == MediaType.IMAGE) {
                hasImage = true;
            }
        }
        return new PendingMessage(userId, List.copyOf(b.messages), sb.toString(), hasImage,
                b.messages.get(0).timestamp(), b.messages.get(b.messages.size() - 1).timestamp());
    }
}