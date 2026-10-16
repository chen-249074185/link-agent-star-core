package com.star.agent.memory;

import com.star.agent.memory.dao.ConversationDao;
import com.star.agent.memory.dao.DailySummaryDao;
import com.star.agent.memory.domain.ConversationDo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// 历史对话服务：conversation 表有 session_id 列（一段对话的身份证）——
// 列表按 session_id 分组、详情/删除按 session_id 定位、继续对话复用同一 session_id
// 老数据（无 session_id）惰性回填：查历史时发现未打标行，按"间隔 > 30 分钟开新会话"打标一次，之后不再猜
@Component
public class ChatHistoryService {

    private static final Logger logger = LoggerFactory.getLogger(ChatHistoryService.class);

    private static final Duration SESSION_GAP = Duration.ofMinutes(30);
    private static final int MAX_ROWS = 500;      // 最多看 500 条流水（够覆盖多天）
    private static final int MAX_TITLE = 20;      // 标题截断长度

    private final ConversationDao dao;
    private final DailySummaryDao summaryDao;
    private final EphemeralMemory ephemeralMemory;

    @Autowired   // 多构造器时 Spring 靠它挑
    public ChatHistoryService(ConversationDao dao, DailySummaryDao summaryDao, EphemeralMemory ephemeralMemory) {
        this.dao = dao;
        this.summaryDao = summaryDao;
        this.ephemeralMemory = ephemeralMemory;
    }

    // 会话列表（最新在前）：{sessionId, title, time, count}
    public List<Map<String, Object>> list(String userId) {
        backfillIfNeeded(userId);
        List<ConversationDo> rows = dao.findByUserIdAsc(userId, MAX_ROWS);
        List<Map<String, Object>> out = new ArrayList<>();
        for (List<ConversationDo> bucket : groupBySession(rows)) {
            out.add(bucketInfo(bucket));
        }
        Collections.reverse(out);   // 按时间正序分桶，返回最新在前
        return out;
    }

    // 会话详情：{role, content, time} 列表（正序）；会话不存在返回空列表
    public List<Map<String, Object>> detail(String userId, String sessionId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ConversationDo row : dao.findBySession(userId, sessionId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", row.getRole());
            m.put("content", row.getContent());
            m.put("time", row.getCreatedAt() == null ? null : row.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            out.add(m);
        }
        return out;
    }

    // 删除会话；会话不存在返回 false
    public boolean delete(String userId, String sessionId) {
        int n = dao.deleteBySession(userId, sessionId);
        logger.info("删除会话 {}: {} 条", sessionId, n);
        return n > 0;
    }

    // 清空该用户的全部记忆：MySQL 短期记忆 + 每日摘要 + Redis 瞬时记忆三处全清
    // （小程序记忆页"清空全部"与微信"删除聊天记录"命令同语义）
    public int clearAll(String userId) {
        int conversations = dao.deleteByUserId(userId);
        int summaries = summaryDao.deleteByUserId(userId);
        ephemeralMemory.delete(userId);
        logger.info("用户 {} 记忆已全部清空：对话 {} 条、每日摘要 {} 条、瞬时记忆已删",
                userId, conversations, summaries);
        return conversations;
    }

    // 搜索：命中内容按 session_id 归并成会话（最新在前）
    public List<Map<String, Object>> search(String userId, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return Collections.emptyList();
        }
        backfillIfNeeded(userId);
        List<ConversationDo> rows = dao.searchByKeyword(userId, keyword.trim(), MAX_ROWS);
        Collections.reverse(rows);   // 搜索是倒序，翻成正序再分组
        List<Map<String, Object>> out = new ArrayList<>();
        for (List<ConversationDo> bucket : groupBySession(rows)) {
            out.add(bucketInfo(bucket));
        }
        Collections.reverse(out);
        return out;
    }

    // 该会话是否属于该用户（继续对话前校验）
    public boolean owns(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        return userId.equals(dao.findSessionOwner(sessionId));
    }

    // 生成新会话 id（前端/服务端共用格式）
    public static String newSessionId() {
        return "s-" + UUID.randomUUID().toString().substring(0, 12);
    }

    // ===== 内部 =====

    // 老数据回填：无 session_id 的行按时间窗打标（幂等，有标即跳过）
    private void backfillIfNeeded(String userId) {
        List<ConversationDo> nulls = dao.findNullSession(userId, MAX_ROWS);
        if (nulls.isEmpty()) {
            return;
        }
        logger.info("历史会话回填 {}: {} 条无标记流水", userId, nulls.size());
        for (List<ConversationDo> bucket : groupByGap(nulls)) {
            String sid = newSessionId();
            for (ConversationDo row : bucket) {
                dao.updateSession(row.getId(), sid);
            }
        }
    }

    // 按 session_id 分组（同会话连续；保持行序）
    private List<List<ConversationDo>> groupBySession(List<ConversationDo> rows) {
        Map<String, List<ConversationDo>> groups = new LinkedHashMap<>();
        for (ConversationDo row : rows) {
            groups.computeIfAbsent(row.getSessionId(), k -> new ArrayList<>()).add(row);
        }
        return new ArrayList<>(groups.values());
    }

    // 时间窗分组：gap > 30 分钟开新桶（仅回填用）
    private List<List<ConversationDo>> groupByGap(List<ConversationDo> rows) {
        List<List<ConversationDo>> buckets = new ArrayList<>();
        List<ConversationDo> cur = null;
        LocalDateTime prev = null;
        for (ConversationDo row : rows) {
            LocalDateTime t = row.getCreatedAt();
            if (cur == null || prev == null || t == null
                    || Duration.between(prev, t).compareTo(SESSION_GAP) > 0) {
                cur = new ArrayList<>();
                buckets.add(cur);
            }
            cur.add(row);
            prev = t;
        }
        return buckets;
    }

    // 会话信息：标题=首条 user 消息前 20 字（无 user 取首条）；时间=最后一条；count=条数
    private Map<String, Object> bucketInfo(List<ConversationDo> bucket) {
        String title = null;
        for (ConversationDo row : bucket) {
            if ("user".equals(row.getRole())) {
                title = row.getContent();
                break;
            }
        }
        if (title == null) {
            title = bucket.get(0).getContent();
        }
        String plain = title == null ? "" : title.replaceAll("\\s+", " ").trim();
        if (plain.length() > MAX_TITLE) {
            plain = plain.substring(0, MAX_TITLE) + "…";
        }
        ConversationDo last = bucket.get(bucket.size() - 1);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", last.getSessionId());
        m.put("title", plain);
        m.put("time", last.getCreatedAt() == null ? null : last.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        m.put("count", bucket.size());
        return m;
    }
}