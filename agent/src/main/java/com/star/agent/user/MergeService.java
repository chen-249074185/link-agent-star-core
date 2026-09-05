package com.star.agent.user;

import com.star.agent.memory.EphemeralMemory;
import com.star.agent.memory.dao.ConversationDao;
import com.star.agent.memory.dao.DailySummaryDao;
import com.star.agent.mcp.McpConnection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

// 账号合并（冻结设计：保留发起方，被并入方须为纯邮箱账号）：
//   MySQL 事务（对话/摘要/猎聘 Token 归属迁移 + 删被并入账号）——
//   rag 知识库迁移在事务内先做，失败抛异常 → 整体回滚、被并入账号不删（补偿语义，可重试）
//   Redis 瞬时记忆迁移失败仅记日志（可接受，不影响主合并）
@Service
public class MergeService {

    private final ConversationDao conversationDao;
    private final DailySummaryDao dailySummaryDao;
    private final UserConfigRepository userConfigRepository;
    private final UserRepository userRepository;
    private final EphemeralMemory ephemeralMemory;
    private final Optional<McpConnection> ragMcpConnection;

    @Autowired
    public MergeService(ConversationDao conversationDao,
                        DailySummaryDao dailySummaryDao,
                        UserConfigRepository userConfigRepository,
                        UserRepository userRepository,
                        EphemeralMemory ephemeralMemory,
                        Optional<McpConnection> ragMcpConnection) {
        this.conversationDao = conversationDao;
        this.dailySummaryDao = dailySummaryDao;
        this.userConfigRepository = userConfigRepository;
        this.userRepository = userRepository;
        this.ephemeralMemory = ephemeralMemory;
        this.ragMcpConnection = ragMcpConnection;
    }

    // 执行合并：from（纯邮箱账号）→ to（保留方）；MySQL 侧原子，rag 失败整体回滚
    @Transactional
    public void merge(String fromUserId, String toUserId) {
        if (fromUserId.equals(toUserId)) {
            return;
        }
        // ① rag 知识库迁移（mock 模式无连接 → 跳过；失败 → 抛异常回滚，被并入账号不删）
        ragMcpConnection.ifPresent(conn -> conn.callTool("rag_transfer_kb",
                Map.of("user_id", fromUserId, "to_user_id", toUserId)));
        // ② 对话记录 + 每日摘要改归属（历史全保留）
        conversationDao.transferUserId(fromUserId, toUserId);
        dailySummaryDao.transferUserId(fromUserId, toUserId);
        // ②⑤ 累计 token 用量并入（删源前先取，避免取不到）
        long fromTokens = userRepository.findTotalTokens(fromUserId);
        if (fromTokens > 0) {
            userRepository.addTokens(toUserId, fromTokens);
        }
        // ③ 猎聘 Token：目标已有则删源的，没有则迁源的（A 有留 A 无取 B）
        if (userConfigRepository.findByUserId(toUserId) != null) {
            userConfigRepository.deleteLiepinToken(fromUserId);
        } else {
            userConfigRepository.transferLiepinToken(fromUserId, toUserId);
        }
        // ④ Redis 瞬时记忆并入（失败仅日志）
        ephemeralMemory.mergeFrom(fromUserId, toUserId);
        // ⑥ 删被并入账号（users 表，最后一步）
        userRepository.deleteByUserId(fromUserId);
    }
}