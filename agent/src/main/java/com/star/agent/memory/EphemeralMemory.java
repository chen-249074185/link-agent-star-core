package com.star.agent.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.star.agent.governance.RedisKeys;
import com.star.agent.llm.ChatMessage;
import com.star.agent.llm.ChatResponse;
import com.star.agent.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

// 瞬时记忆：会话上下文存 Redis，TTL 30 分钟（超时自动遗忘）
// 轻量压缩：消息超 10 轮（20 条）时，保留最近 10 条 + 用 LLM 把老消息压成一句摘要（失败降级纯截断）
@Component
public class EphemeralMemory {

    private static final Logger logger = LoggerFactory.getLogger(EphemeralMemory.class);

    private static final long TTL_SECONDS = 30 * 60;   // 30 分钟：瞬时记忆的保质期
    private static final int MAX_MESSAGES = 20;        // 10 轮对话（1 轮 = 用户+回答 2 条），超过就压缩
    private static final int KEEP_RECENT = 10;         // 压缩后保留最近 10 条

    private final StringRedisTemplate redis;           // String 模板 + 手动 JSON（避免泛型序列化的类型信息坑）
    private final ObjectMapper objectMapper;
    private final LlmClient llmClient;                 // 压缩摘要用（和对话同一个 DeepSeek 大脑）

    // @Qualifier 点名 DeepSeek（坑28：同接口 3 个实现，不点名报歧义）
    public EphemeralMemory(StringRedisTemplate redis,
                           ObjectMapper objectMapper,
                           @Qualifier("deepSeekLlmClient") LlmClient llmClient) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.llmClient = llmClient;
    }

    // 读回某用户的瞬时记忆（无则空列表）
    public List<ChatEntry> load(String userId) {
        String json = redis.opsForValue().get(RedisKeys.memory(userId));
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<List<ChatEntry>>() {});
        } catch (Exception e) {
            logger.warn("瞬时记忆读取失败(JSON损坏?)，清空重来: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    // 整份覆盖保存：TTL 每次写都重置，用户一直聊就一直续命
    public void save(String userId, List<ChatEntry> entries) {
        try {
            String json = objectMapper.writeValueAsString(entries);
            redis.opsForValue().set(RedisKeys.memory(userId), json, Duration.ofSeconds(TTL_SECONDS));
        } catch (Exception e) {
            logger.warn("瞬时记忆写入失败: {}", e.getMessage());
        }
    }

    // 清空某用户的瞬时记忆（删除聊天记录命令用；只删 MySQL 不删这个，对话里还能"回忆"到旧内容）
    public void delete(String userId) {
        try {
            redis.delete(RedisKeys.memory(userId));
        } catch (Exception e) {
            logger.warn("瞬时记忆删除失败: {}", e.getMessage());
        }
    }

    // 账号合并：from 的瞬时记忆并入 to（追加后压缩），然后清空 from；失败仅记日志不阻断合并
    public void mergeFrom(String fromUserId, String toUserId) {
        try {
            List<ChatEntry> fromEntries = load(fromUserId);
            if (fromEntries.isEmpty()) {
                return;
            }
            List<ChatEntry> toEntries = new ArrayList<>(load(toUserId));
            toEntries.addAll(fromEntries);
            save(toUserId, compress(toEntries));
            redis.delete(RedisKeys.memory(fromUserId));
            logger.info("瞬时记忆合并: {} → {}", fromUserId, toUserId);
        } catch (Exception e) {
            logger.warn("瞬时记忆合并失败: {}", e.getMessage());
        }
    }

    // 追加一轮对话（用户说 + AI 答），超限自动压缩后入库
    public void append(String userId, String userMessage, String assistantMessage) {
        List<ChatEntry> entries = load(userId);
        entries.add(new ChatEntry("user", userMessage));
        entries.add(new ChatEntry("assistant", assistantMessage));
        save(userId, compress(entries));
    }

    // 压缩：超过 20 条 → 老消息交给 LLM 压成摘要 + 最近 10 条；LLM 挂了只留最近 10 条（绝不让记忆功能崩溃）
    List<ChatEntry> compress(List<ChatEntry> entries) {
        if (entries.size() <= MAX_MESSAGES) {
            return entries;
        }
        List<ChatEntry> old = new ArrayList<>(entries.subList(0, entries.size() - KEEP_RECENT));
        List<ChatEntry> recent = new ArrayList<>(entries.subList(entries.size() - KEEP_RECENT, entries.size()));

        String summary = summarize(old);
        if (summary != null && !summary.isBlank()) {
            recent.add(0, new ChatEntry("system", "之前的对话摘要：" + summary));
        }
        logger.info("瞬时记忆压缩: 丢弃{}条老消息, 保留最近{}条{}", old.size(), recent.size(),
                summary == null ? "(摘要失败)" : "");
        return recent;
    }

    // 让 LLM 把老消息总结成一句话；任何异常都返回 null（降级信号）
    private String summarize(List<ChatEntry> old) {
        try {
            List<ChatMessage> history = new ArrayList<>();
            for (ChatEntry e : old) {
                history.add(new ChatMessage(e.role(), e.content()));
            }
            history.add(new ChatMessage("user", "用一句话总结以上对话的要点，只输出摘要本身"));
            ChatResponse resp = llmClient.chat(history, List.of());
            return resp.content();
        } catch (Exception e) {
            logger.warn("瞬时记忆摘要生成失败，降级为纯截断: {}", e.getMessage());
            return null;
        }
    }
}