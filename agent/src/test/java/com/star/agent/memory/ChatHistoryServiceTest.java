package com.star.agent.memory;

import com.star.agent.memory.dao.ConversationDao;
import com.star.agent.memory.domain.ConversationDo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// ChatHistoryService 真实 MySQL 集成测试：session_id 语义——分组/详情/删除/搜索/回填
@SpringBootTest(properties = "linkagent.redis-enabled=true")
class ChatHistoryServiceTest {

    @Autowired
    private ChatHistoryService service;

    @Autowired
    private ConversationDao mapper;

    private final String userId = "itest:hist" + System.currentTimeMillis();

    @AfterEach
    void cleanup() {
        mapper.deleteByUserId(userId);
    }

    // 插一条指定会话和时间的流水
    private ConversationDo insert(String role, String content, String sessionId, LocalDateTime time) {
        ConversationDo c = new ConversationDo();
        c.setUserId(userId);
        c.setRole(role);
        c.setContent(content);
        c.setSessionId(sessionId);
        mapper.insert(c);
        mapper.updateCreatedAt(c.getId(), time);
        return c;
    }

    @Test
    void list_groupsBySessionAndOrdersNewestFirst() {
        LocalDateTime base = LocalDateTime.of(2026, 10, 28, 9, 0);
        insert("user", "会话A第一条", "sa", base);
        insert("assistant", "回复", "sa", base.plusMinutes(1));
        insert("user", "会话B第一条", "sb", base.plusHours(5));

        List<Map<String, Object>> list = service.list(userId);
        assertEquals(2, list.size());
        // 最新在前（sb 更晚）
        assertEquals("会话B第一条", list.get(0).get("title"));
        assertEquals("sb", list.get(0).get("sessionId"));
        assertEquals(1, list.get(0).get("count"));
        assertEquals("会话A第一条", list.get(1).get("title"));
        assertEquals(2, list.get(1).get("count"));
    }

    @Test
    void list_longGapSameSession_staysOneSession() {
        // 同 sessionId 但间隔 1 小时：仍是同一会话（这是 session_id 优于时间窗的意义）
        LocalDateTime base = LocalDateTime.of(2026, 10, 28, 9, 0);
        insert("user", "早上聊的", "sc", base);
        insert("assistant", "早上回复", "sc", base.plusMinutes(2));
        insert("user", "一小时后回来接着聊", "sc", base.plusHours(1));

        List<Map<String, Object>> list = service.list(userId);
        assertEquals(1, list.size());
        assertEquals(3, list.get(0).get("count"));
        assertTrue(((String) list.get(0).get("title")).contains("早上聊的"));
    }

    @Test
    void detail_returnsMessagesInOrder() {
        LocalDateTime base = LocalDateTime.of(2026, 10, 28, 9, 0);
        insert("user", "问一", "sd", base);
        insert("assistant", "答一", "sd", base.plusMinutes(1));

        List<Map<String, Object>> detail = service.detail(userId, "sd");
        assertEquals(2, detail.size());
        assertEquals("user", detail.get(0).get("role"));
        assertEquals("答一", detail.get(1).get("content"));
        assertTrue(detail.get(0).get("time") instanceof String);

        // 不存在的会话返回空
        assertTrue(service.detail(userId, "ghost").isEmpty());
    }

    @Test
    void delete_removesOnlyThatSession() {
        LocalDateTime base = LocalDateTime.of(2026, 10, 28, 9, 0);
        insert("user", "要删的", "se", base);
        insert("assistant", "回复", "se", base.plusMinutes(1));
        insert("user", "要留的", "sf", base.plusHours(5));

        assertTrue(service.delete(userId, "se"));
        assertEquals(1, service.list(userId).size());
        assertEquals("要留的", service.list(userId).get(0).get("title"));
        assertFalse(service.delete(userId, "ghost"));
    }

    @Test
    void search_matchesContentAndGroupsHits() {
        LocalDateTime base = LocalDateTime.of(2026, 10, 28, 9, 0);
        insert("user", "北京租房预算", "sg", base);
        insert("assistant", "推荐房源", "sg", base.plusMinutes(1));
        insert("user", "杭州天气怎么样", "sh", base.plusHours(5));

        List<Map<String, Object>> hits = service.search(userId, "房源");
        assertEquals(1, hits.size());
        assertEquals("sg", hits.get(0).get("sessionId"));

        assertTrue(service.search(userId, "不存在的词xyz").isEmpty());
    }

    @Test
    void owns_checksSessionOwner() {
        insert("user", "A的会话", "sx", LocalDateTime.now());
        assertTrue(service.owns(userId, "sx"));
        assertFalse(service.owns("other-user", "sx"));   // 跨用户拒绝
        assertFalse(service.owns(userId, "ghost"));
    }

    @Test
    void backfill_marksNullSessionsByGap() {
        LocalDateTime base = LocalDateTime.of(2026, 10, 28, 9, 0);
        // 老数据：无 session_id（模拟历史遗留）
        ConversationDo a = new ConversationDo();
        a.setUserId(userId);
        a.setRole("user");
        a.setContent("老会话一");
        mapper.insert(a);
        mapper.updateCreatedAt(a.getId(), base);
        ConversationDo b = new ConversationDo();
        b.setUserId(userId);
        b.setRole("assistant");
        b.setContent("老回复");
        mapper.insert(b);
        mapper.updateCreatedAt(b.getId(), base.plusMinutes(1));
        ConversationDo c = new ConversationDo();
        c.setUserId(userId);
        c.setRole("user");
        c.setContent("老会话二（两小时后）");
        mapper.insert(c);
        mapper.updateCreatedAt(c.getId(), base.plusHours(2));

        List<Map<String, Object>> list = service.list(userId);   // 首次查触发回填
        assertEquals(2, list.size(), "两小时后应拆成两个会话");
        assertEquals(1, list.get(0).get("count"));
        assertEquals(2, list.get(1).get("count"));
        // 回填后无 null 残留
        assertTrue(mapper.findNullSession(userId, 10).isEmpty());
        // 幂等：再次查仍是 2 个
        assertEquals(2, service.list(userId).size());
    }
}