package com.star.agent.memory.dao;
import com.star.agent.memory.domain.ConversationDo;

import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

// 短期记忆 Mapper：conversation 表的增查（SQL 写在 resources/mapper/ConversationDao.xml）
@Mapper
public interface ConversationDao {

    // 存一条对话（user/assistant 都要落库）
    int insert(ConversationDo conversation);

    // 查某用户最近的对话（按时间倒序，limit 条；用户端历史记录也用这个）
    List<ConversationDo> findByUserId(String userId, int limit);

    // 查某用户某一天的对话（当日压缩取料用）
    List<ConversationDo> findByUserIdAndDate(String userId, LocalDate date);

    // 查所有有对话记录的用户 id（补压时遍历用）
    List<String> findDistinctUserIds();

    // 改某条对话的时间（测试造"昨天"数据用）
    int updateCreatedAt(Long id, LocalDateTime createdAt);

    // 删某用户的全部对话（测试清理用）
    int deleteByUserId(String userId);

    // 账号合并：某用户全部对话改归另一用户（B 并入 A 后 B 删，历史保留）
    int transferUserId(String fromUserId, String toUserId);

    // 查某用户最近 limit 条（时间正序，最早的在前；历史会话分组用）
    List<ConversationDo> findByUserIdAsc(String userId, int limit);

    // 查某用户一个会话的全部消息（会话详情/继续对话取上下文用）
    List<ConversationDo> findBySession(String userId, String sessionId);

    // 删某用户的一个会话
    int deleteBySession(String userId, String sessionId);

    // 查某用户还没打会话标记的行（老数据回填扫描用）
    List<ConversationDo> findNullSession(String userId, int limit);

    // 给某条消息打会话标记（老数据回填）
    int updateSession(Long id, String sessionId);

    // 查某会话属于哪个用户（防跨用户伪造 sessionId）
    String findSessionOwner(String sessionId);

    // 关键词搜内容（历史搜索用，时间倒序）
    List<ConversationDo> searchByKeyword(String userId, String keyword, int limit);
}