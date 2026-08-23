package com.star.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.star.agent.llm.ChatResponse;
import com.star.agent.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 压缩逻辑纯单测：不连 Redis（compress 是包可见方法，直接测）
class EphemeralMemoryCompressTest {

    private final LlmClient llmClient = mock(LlmClient.class);
    private final EphemeralMemory memory = new EphemeralMemory(null, new ObjectMapper(), llmClient);

    // 造 N 条交替对话（1 轮 = 用户 + 回答）
    private List<ChatEntry> turns(int n) {
        List<ChatEntry> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            list.add(new ChatEntry("user", "问题" + i));
            list.add(new ChatEntry("assistant", "回答" + i));
        }
        return list;
    }

    // 不超过 10 轮（20 条）：原样返回，不动
    @Test
    void underLimit_isUnchanged() {
        List<ChatEntry> in = turns(10);
        List<ChatEntry> out = memory.compress(in);
        assertEquals(20, out.size());
    }

    // 超 10 轮：LLM 摘要成功 → 摘要(system) + 最近 10 条
    @Test
    void overLimit_keepsRecentPlusSummary() {
        when(llmClient.chat(anyList(), anyList()))
                .thenReturn(new ChatResponse("用户问了 11 个问题", List.of()));

        List<ChatEntry> in = turns(11);   // 22 条 = 超 2 条
        List<ChatEntry> out = memory.compress(in);

        assertEquals(11, out.size());     // 1 条摘要 + 10 条最近
        assertEquals("system", out.get(0).role());
        assertTrue(out.get(0).content().contains("用户问了 11 个问题"));
        // 最近 10 条从第 2 轮（问题7）开始：问题7/回答7...问题11/回答11
        assertEquals("问题7", out.get(1).content());
        assertEquals("回答11", out.get(10).content());
    }

    // 超 10 轮但 LLM 挂了：降级为纯截断（只留最近 10 条，不崩）
    @Test
    void overLimit_llmFails_fallsBackToTruncation() {
        when(llmClient.chat(anyList(), anyList()))
                .thenThrow(new RuntimeException("网络错误"));

        List<ChatEntry> in = turns(12);
        List<ChatEntry> out = memory.compress(in);

        assertEquals(10, out.size());     // 没摘要，就最近 10 条
        assertEquals("回答12", out.get(9).content());
    }

    // 边界：恰好 10 轮整（20 条）不压缩；第 21 条才触发
    @Test
    void boundary_exactLimit_noCompress() {
        when(llmClient.chat(anyList(), anyList()))
                .thenReturn(new ChatResponse("摘要", List.of()));

        List<ChatEntry> in = turns(10);
        in.add(new ChatEntry("user", "第21条"));
        List<ChatEntry> out = memory.compress(in);
        assertEquals(11, out.size());     // 21 条 > 20 → 压缩成 1 摘要 + 10 最近
        assertEquals("system", out.get(0).role());
    }
}