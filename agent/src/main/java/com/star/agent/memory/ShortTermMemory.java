package com.star.agent.memory;
import com.star.agent.memory.dao.ConversationDao;
import com.star.agent.memory.domain.ConversationDo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

// 短期记忆：对话永久落 MySQL（conversation 表），与瞬时记忆（Redis 30 分钟）互补
// 双写策略：Redis 管"此刻快读"，MySQL 管"永久留存"；落库放异步线程，不阻塞对话返回
@Component
public class ShortTermMemory {

    private static final Logger logger = LoggerFactory.getLogger(ShortTermMemory.class);

    private final ConversationDao mapper;

    public ShortTermMemory(ConversationDao mapper) {
        this.mapper = mapper;
    }

    // 异步落库：用户消息和 AI 回答都存；写库失败只记日志，绝不影响对话主流程
    public void saveAsync(String userId, String role, String content) {
        CompletableFuture.runAsync(() -> {
            try {
                ConversationDo c = new ConversationDo();
                c.setUserId(userId);
                c.setRole(role);
                c.setContent(content);
                mapper.insert(c);
            } catch (Exception e) {
                logger.warn("短期记忆落库失败: {}", e.getMessage());
            }
        });
    }

    // 查某用户最近的历史（倒序，最新的在前）；用户端历史记录页用
    public List<ConversationDo> findHistory(String userId, int limit) {
        return mapper.findByUserId(userId, limit);
    }
}