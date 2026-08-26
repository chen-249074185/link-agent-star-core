package com.star.agent.memory;
import com.star.agent.memory.dao.ConversationDao;
import com.star.agent.memory.dao.DailySummaryDao;
import com.star.agent.memory.domain.ConversationDo;
import com.star.agent.memory.domain.DailySummaryDo;

import com.star.agent.llm.ChatResponse;
import com.star.agent.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// DailySummaryService 压缩逻辑测试：真实 MySQL 表 + mock LLM（不花真实 token）
@SpringBootTest(properties = "linkagent.redis-enabled=true")
class DailySummaryServiceTest {

    @Autowired
    private ConversationDao conversationDao;

    @Autowired
    private DailySummaryDao summaryMapper;

    private final String userId = "itest:sum" + System.currentTimeMillis();
    private final LocalDate yesterday = LocalDate.now().minusDays(1);

    @org.junit.jupiter.api.BeforeEach
    @org.junit.jupiter.api.AfterEach
    void cleanup() {
        conversationDao.deleteByUserId(userId);
        summaryMapper.deleteByUserId(userId);
    }

    private DailySummaryService newService(String summaryText) {
        LlmClient llm = mock(LlmClient.class);
        when(llm.chat(anyList(), anyList())).thenReturn(new ChatResponse(summaryText, List.of()));
        return new DailySummaryService(conversationDao, summaryMapper, llm);
    }

    // 昨天有对话 → 压出摘要存库
    @Test
    void compressMissing_producesSummaryForYesterday() {
        // 造昨天一条流水（用短内容，靠 SQL 的 date 范围取）
        ConversationDo c = new ConversationDo();
        c.setUserId(userId);
        c.setRole("user");
        c.setContent("我想在杭州滨江区租房");
        conversationDao.insert(c);
        // 把 created_at 改成昨天：直接 update SQL（MyBatis insert 用默认当前时间）
        conversationDao.updateCreatedAt(c.getId(), yesterday.atTime(10, 0));

        DailySummaryService service = newService("- 用户想在杭州滨江区租房");
        service.compressMissing(List.of(userId));

        List<DailySummaryDo> summaries = summaryMapper.findByUserId(userId, 10);
        assertEquals(1, summaries.size());
        assertEquals(yesterday, summaries.get(0).getSummaryDate());
        assertTrue(summaries.get(0).getContent().contains("杭州滨江区"));
    }

    // 没缺口（昨天已压过）→ 不重复压
    @Test
    void compressMissing_noGap_skips() {
        // 先造一条"已压过昨天"的摘要
        DailySummaryDo existing = new DailySummaryDo();
        existing.setUserId(userId);
        existing.setSummaryDate(yesterday);
        existing.setContent("已有摘要");
        summaryMapper.insert(existing);

        DailySummaryService service = newService("新摘要");
        service.compressMissing(List.of(userId));

        // 仍只有一条（没新增）
        assertEquals(1, summaryMapper.findByUserId(userId, 10).size());
    }

    // 当天（今天）的对话不压（今天没过完）
    @Test
    void compressMissing_todayNotCompressed() {
        ConversationDo c = new ConversationDo();
        c.setUserId(userId);
        c.setRole("user");
        c.setContent("今天聊的");
        conversationDao.insert(c);   // created_at = 现在（今天）

        DailySummaryService service = newService("摘要");
        service.compressMissing(List.of(userId));

        assertEquals(0, summaryMapper.findByUserId(userId, 10).size());
    }
}