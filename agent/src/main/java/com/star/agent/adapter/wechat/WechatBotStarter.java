package com.star.agent.adapter.wechat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// 微信 Bot 启动器（Spring 化接线）：应用启动时自动拉起微信 Bot
// 生命周期统一交给 BotManager（扫表恢复/新增/登出），Bot 线程由 BotRegistry 管理
@Component
@ConditionalOnProperty(name = "linkagent.wechat.bot-enabled", havingValue = "true", matchIfMissing = false)
public class WechatBotStarter implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(WechatBotStarter.class);

    private final BotManager botManager;

    public WechatBotStarter(BotManager botManager) {
        this.botManager = botManager;
    }

    @Override
    public void run(ApplicationArguments args) {
        botManager.startAll();
        logger.info("微信 Bot 启动器完成");
    }
}