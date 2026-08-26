package com.star.agent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 压缩任务触发器（三个入口，都调同一个 compressMissing 查缺口补压）：
//   ① 启动时：ApplicationRunner 立即补压（程序关几天再开也补齐）——主机制
//   ② 定时 00:30：@Scheduled 顺带压当天——辅助
//   ③ 惰性：ChatService 跨天对话前调 compressMissing(userId)——双保险
@Component
public class MemoryCompressionScheduler implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(MemoryCompressionScheduler.class);

    private final DailySummaryService dailySummaryService;
    private final ConversationDao conversationDao;

    public MemoryCompressionScheduler(DailySummaryService dailySummaryService,
                                      ConversationDao conversationDao) {
        this.dailySummaryService = dailySummaryService;
        this.conversationDao = conversationDao;
    }

    // ① 启动补压：Spring 起来后自动跑一次，把缺口日期全补上
    @Override
    public void run(ApplicationArguments args) {
        logger.info("启动补偿：检查每日摘要缺口...");
        dailySummaryService.compressMissing(conversationDao.findDistinctUserIds());
    }

    // ② 定时 00:30：程序刚好开着就顺手压，错过也无妨（启动/惰性会补）
    @Scheduled(cron = "0 30 0 * * ?")
    public void scheduledCompress() {
        logger.info("定时压缩：00:30 例行检查");
        dailySummaryService.compressMissing(conversationDao.findDistinctUserIds());
    }
}