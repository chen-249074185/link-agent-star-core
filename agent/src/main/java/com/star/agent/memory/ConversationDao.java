package com.star.agent.memory;

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
}