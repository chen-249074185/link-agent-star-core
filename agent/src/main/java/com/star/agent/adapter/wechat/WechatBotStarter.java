package com.star.agent.adapter.wechat;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.star.agent.scene.SceneDetector;
import com.star.agent.service.ChatService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// 微信 Bot 启动器（Spring 化接线）：应用启动时自动拉起微信 Bot
// 作用：微信对话走容器内的真实组件（Redis/MySQL 记忆、技能），不再用手动 main 链路的 null 降级
// 登录：sessions 目录有已保存会话 → 免扫码恢复；没有 → 打印二维码等扫码（第一次）
// W11 升级点：此处单 Bot 启动，届时改为扫表多 Bot 逐个 resume（BotRegistry + wechat_session 表）
@Component
@ConditionalOnProperty(name = "linkagent.wechat.bot-enabled", havingValue = "true", matchIfMissing = false)
public class WechatBotStarter implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(WechatBotStarter.class);

    private final ChatService chatService;
    private final SceneDetector sceneDetector;
    private BotWorker worker;

    public WechatBotStarter(ChatService chatService, SceneDetector sceneDetector) {
        this.chatService = chatService;
        this.sceneDetector = sceneDetector;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // 找已保存会话：取最新的一份（多 Bot 版遍历全部）
        String botId = SessionStore.latestBotId();
        if (botId != null) {
            logger.info("检测到已保存会话 botId={}，尝试免扫码恢复", botId);
        } else {
            logger.info("无已保存会话，首次启动需要扫码登录");
        }

        ILinkClient client = ILinkClient.builder()
                .config(ILinkConfigFactory.create())
                .build();
        ILinkMessageAdapter adapter = new ILinkMessageAdapter();
        worker = new BotWorker(botId, client, adapter, chatService, sceneDetector);
        Thread thread = new Thread(worker, "bot-" + (botId == null ? "new" : botId));
        thread.setDaemon(true);
        thread.start();
        logger.info("微信 Bot 线程已启动");
    }

    // 应用关闭时停掉 Bot（dispatcher 双线程随之停止）
    @PreDestroy
    public void shutdown() {
        if (worker != null) {
            worker.stop();
        }
    }
}