package com.star.agent.adapter.wechat;

import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.List;

// 微信会话 Mapper：wechat_session 表的增删查（SQL 写在 resources/mapper/WechatSessionDao.xml）
@Mapper
public interface WechatSessionDao {

    // 存/更新一条会话（bot_id 唯一键冲突=覆盖旧会话：同 bot 重新登录顶号）
    int upsert(WechatSessionDo session);

    // 按 botId 查会话（免扫码恢复用）
    WechatSessionDo findByBotId(String botId);

    // 查全部会话（启动扫表多 Bot 恢复用）
    List<WechatSessionDo> findAll();

    // 删某 bot 的会话（登出命令/测试清理用）
    int deleteByBotId(String botId);

    // 删 saved_at 早于截止时间的会话（过期清理）
    int deleteOlderThan(LocalDateTime cutoff);

    // 改某条会话的保存时间（测试造"旧数据"用）
    int updateSavedAt(String botId, LocalDateTime savedAt);

    // 会话总数（统计/调试）
    long count();
}