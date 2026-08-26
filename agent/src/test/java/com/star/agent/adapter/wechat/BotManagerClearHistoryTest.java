package com.star.agent.adapter.wechat;

import com.star.agent.memory.EphemeralMemory;
import com.star.agent.memory.dao.ConversationDao;
import com.star.agent.memory.dao.DailySummaryDao;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

// BotManager 聊天记录清空单测：mock 三个存储依赖，验证 clearChatHistory 三处全清
class BotManagerClearHistoryTest {

    private final ConversationDao conversationDao = mock(ConversationDao.class);
    private final DailySummaryDao dailySummaryDao = mock(DailySummaryDao.class);
    private final EphemeralMemory ephemeralMemory = mock(EphemeralMemory.class);

    private BotManager newBotManager() {
        return new BotManager(mock(com.star.agent.service.ChatService.class),
                mock(com.star.agent.scene.SceneDetector.class),
                mock(WechatSessionRepository.class),
                mock(BotRegistry.class),
                mock(com.star.agent.adapter.MultimediaHandler.class),
                mock(com.star.agent.user.UserConfigRepository.class),
                mock(com.star.agent.adapter.DebounceProcessor.class),
                conversationDao, dailySummaryDao, ephemeralMemory,
                mock(com.star.agent.adapter.ImageFetcher.class));
    }

    // 清空某用户聊天记录：MySQL 对话 + 每日摘要 + Redis 瞬时记忆三处全清
    @Test
    void clearChatHistory_deletesAllThreeStores() {
        BotManager manager = newBotManager();

        manager.clearChatHistory("wx_u1");

        verify(conversationDao).deleteByUserId("wx_u1");
        verify(dailySummaryDao).deleteByUserId("wx_u1");
        verify(ephemeralMemory).delete("wx_u1");
    }

    // 只清指定用户，不碰其他人
    @Test
    void clearChatHistory_onlyTargetsGivenUser() {
        BotManager manager = newBotManager();

        manager.clearChatHistory("u1");

        verify(conversationDao).deleteByUserId("u1");
        verify(dailySummaryDao).deleteByUserId("u1");
        verify(ephemeralMemory).delete("u1");
    }
}