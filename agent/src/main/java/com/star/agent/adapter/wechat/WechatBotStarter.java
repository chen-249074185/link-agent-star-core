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

import java.util.List;

// 微信 Bot 启动器（Spring 化接线）：应用启动时自动拉起微信 Bot
// 作用：微信对话走容器内的真实组件（Redis/MySQL 记忆、技能），不再用手动 main 链路的 null 降级
// 登录：wechat_session 表有已保存会话 → 逐个免扫码恢复；没有 → 打印二维码等扫码（第一次）
// 会话持久化用数据库版仓库（扫表多 Bot），Bot 线程统一由 BotRegistry 管理
@Component
@ConditionalOnProperty(name = "linkagent.wechat.bot-enabled", havingValue = "true", matchIfMissing = false)
public class WechatBotStarter implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(WechatBotStarter.class);

    private final ChatService chatService;
    private final SceneDetector sceneDetector;
    private final WechatSessionRepository sessionRepository;
    private final BotRegistry botRegistry;

    public WechatBotStarter(ChatService chatService, SceneDetector sceneDetector,
                            WechatSessionRepository sessionRepository, BotRegistry botRegistry) {
        this.chatService = chatService;
        this.sceneDetector = sceneDetector;
        this.sessionRepository = sessionRepository;
        this.botRegistry = botRegistry;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> botIds = sessionRepository.findAllBotIds();
        if (botIds.isEmpty()) {
            logger.info("无已保存会话，首次启动需要扫码登录");
            startBot(null);
            return;
        }
        for (String botId : botIds) {
            logger.info("检测到已保存会话 botId={}，尝试免扫码恢复", botId);
            startBot(botId);
        }
    }

    private void startBot(String botId) {
        ILinkClient client = ILinkClient.builder()
                .config(ILinkConfigFactory.create())
                .build();
        ILinkMessageAdapter adapter = new ILinkMessageAdapter();
        BotWorker worker = new BotWorker(botId, client, adapter, chatService, sceneDetector, sessionRepository);
        botRegistry.start(botId == null ? "new" : botId, worker);
    }

    // 应用关闭时停掉全部 Bot（各 worker 的 dispatcher 双线程随之停止）
    @PreDestroy
    public void shutdown() {
        botRegistry.stopAll();
    }
}