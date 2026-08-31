package com.star.agent.adapter.wechat;

import com.star.agent.adapter.DebounceProcessor;
import com.star.agent.adapter.ImageFetcher;
import com.star.agent.adapter.MultimediaHandler;
import com.star.agent.adapter.ReplySender;
import com.star.agent.memory.EphemeralMemory;
import com.star.agent.memory.dao.ConversationDao;
import com.star.agent.memory.dao.DailySummaryDao;
import com.star.agent.scene.SceneDetector;
import com.star.agent.service.ChatService;
import com.star.agent.user.UserConfigRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// BotManager 扫码上线幂等单测（11-01 解耦）：同微信已有 Bot 在线 → 只存会话不重启；
// 无 Bot 在线 → 才停旧清旧新起（顶号场景）
class BotManagerStartFromScanTest {

    private BotManager newManager(BotRegistry registry, WechatSessionRepository repo) {
        return new BotManager(mock(ChatService.class), mock(SceneDetector.class), repo, registry,
                mock(MultimediaHandler.class), mock(UserConfigRepository.class), mock(DebounceProcessor.class),
                mock(ConversationDao.class), mock(DailySummaryDao.class), mock(EphemeralMemory.class),
                mock(ImageFetcher.class), mock(WechatIdentityResolver.class));
    }

    // 同微信已有 Bot 在线：扫码确认只更新会话，不重启（否则新旧 worker 交接 → missing context token）
    @Test
    void startFromScan_wechatAlreadyOnline_onlySavesSession() {
        BotRegistry registry = mock(BotRegistry.class);
        WechatSessionRepository repo = mock(WechatSessionRepository.class);
        when(registry.hasBotForWechat("wx@im.wechat")).thenReturn(true);
        BotManager manager = newManager(registry, repo);

        assertDoesNotThrow(() -> manager.startFromScan("bt", "wx@im.wechat", "b1", "url"));

        verify(repo).save(any());                                  // 会话还是要更新
        verify(registry, never()).stopAllWithUserId(anyString()); // 但绝不重启/顶号
        verify(registry, never()).start(anyString(), any());       // 不启动新 worker
    }

    // 无该微信 Bot 在线：停旧清旧并新起（#newbot 顶号场景，一个微信只能有一个 bot）
    @Test
    void startFromScan_wechatOffline_startsNewBot() {
        BotRegistry registry = mock(BotRegistry.class);
        WechatSessionRepository repo = mock(WechatSessionRepository.class);
        when(registry.hasBotForWechat("wx@im.wechat")).thenReturn(false);
        BotManager manager = newManager(registry, repo);

        assertDoesNotThrow(() -> manager.startFromScan("bt", "wx@im.wechat", "b1", "url"));

        verify(registry).stopAllWithUserId("wx@im.wechat");        // 顶号清理
        verify(repo).deleteByUserId("wx@im.wechat");               // 清旧会话
        verify(registry).start(anyString(), any());                // 启动新 worker
    }
}