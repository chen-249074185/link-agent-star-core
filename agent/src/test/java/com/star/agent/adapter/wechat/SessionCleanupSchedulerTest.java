package com.star.agent.adapter.wechat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

// 会话过期清理调度器测试：启动即清 + 定时入口清（都调 deleteOlderThan(7)）
// bot-enabled=false 防止测试上下文真的拉起微信 Bot 扫码
@SpringBootTest(properties = "linkagent.wechat.bot-enabled=false")
class SessionCleanupSchedulerTest {

    @Autowired
    SessionCleanupScheduler scheduler;

    @SpyBean
    WechatSessionRepository sessionRepository;

    // 启动入口：手动触发 run（runner 由 Spring 在真实启动时调用，测试里显式调）
    @Test
    void startup_cleansExpiredSessions() {
        scheduler.run(null);

        verify(sessionRepository, atLeastOnce()).deleteOlderThan(eq(SessionCleanupScheduler.EXPIRE_DAYS));
    }

    // 定时入口：手动触发一次也走同一清理
    @Test
    void scheduledCleanup_cleansExpiredSessions() {
        scheduler.scheduledCleanup();

        verify(sessionRepository, atLeastOnce()).deleteOlderThan(eq(SessionCleanupScheduler.EXPIRE_DAYS));
    }
}