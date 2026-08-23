package com.star.agent.memory;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;

// 短期记忆 Mapper：conversation 表的增查（SQL 写在 resources/mapper/ConversationMapper.xml）
@Mapper
public interface ConversationMapper {

    // 存一条对话（user/assistant 都要落库）
    int insert(Conversation conversation);

    // 查某用户最近的对话（按时间倒序，limit 条；W12 用户端历史记录也用这个）
    List<Conversation> findByUserId(String userId, int limit);

    // 删某用户的全部对话（测试清理用）
    int deleteByUserId(String userId);
}