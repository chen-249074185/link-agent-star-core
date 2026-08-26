package com.star.agent.adapter.wechat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 会话过期清理触发器（两个入口，都调同一个 deleteOlderThan 删 7 天前的会话）：
//   ① 启动时：ApplicationRunner 立即清一次——主机制（程序关几天再开，先清再扫表恢复）
//   ② 定时 03:30：@Scheduled 顺带清——辅助（错过也无妨，启动会补）
// 语义：Bot 每次登录/恢复成功都会刷新 saved_at（续期），7 天没成功续期的会话视为失效清掉
@Component
public class SessionCleanupScheduler implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(SessionCleanupScheduler.class);

    // 过期天数：会话超过 N 天没续期就清（防表膨胀 + 防 bot_id 被僵尸会话占位）
    static final int EXPIRE_DAYS = 7;

    private final WechatSessionRepository sessionRepository;

    public SessionCleanupScheduler(WechatSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    // ① 启动清理：扫表恢复前先删过期会话
    @Override
    public void run(ApplicationArguments args) {
        cleanup();
    }

    // ② 定时清理：每天 03:30 例行（避开压缩任务 00:30）
    @Scheduled(cron = "0 30 3 * * ?")
    public void scheduledCleanup() {
        cleanup();
    }

    private void cleanup() {
        int deleted = sessionRepository.deleteOlderThan(EXPIRE_DAYS);
        logger.info("过期会话清理：删除 {} 天前的 {} 条会话", EXPIRE_DAYS, deleted);
    }
}