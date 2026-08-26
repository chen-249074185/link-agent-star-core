package com.star.agent.adapter.wechat;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.star.agent.scene.SceneDetector;
import com.star.agent.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

// Bot 生命周期管理：启动扫表恢复 + 运行中新增（#newbot）+ 登出（#logout）
// 线程统一由 BotRegistry 管理，会话持久化走数据库仓库
@Component
public class BotManager {

    private static final Logger logger = LoggerFactory.getLogger(BotManager.class);

    private final ChatService chatService;
    private final SceneDetector sceneDetector;
    private final WechatSessionRepository sessionRepository;
    private final BotRegistry botRegistry;

    public BotManager(ChatService chatService, SceneDetector sceneDetector,
                      WechatSessionRepository sessionRepository, BotRegistry botRegistry) {
        this.chatService = chatService;
        this.sceneDetector = sceneDetector;
        this.sessionRepository = sessionRepository;
        this.botRegistry = botRegistry;
    }

    // 启动恢复：扫表逐个免扫码恢复；空表 → 起一个新 Bot 等扫码
    public void startAll() {
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

    // 新增一个 Bot（#newbot 命令）：botId=null 走扫码，二维码打印在服务器控制台
    public void launchNew() {
        logger.info("收到 #newbot 命令，发起新 Bot 登录");
        startBot(null);
    }

    // 登出一个 Bot（#logout 命令）：停线程 + 删数据库会话（重启不再恢复）
    public void logout(String botId) {
        botRegistry.stop(botId);
        sessionRepository.delete(botId);
        logger.info("Bot 已登出并删除会话: {}", botId);
    }

    private void startBot(String botId) {
        ILinkClient client = ILinkClient.builder()
                .config(ILinkConfigFactory.create())
                .build();
        ILinkMessageAdapter adapter = new ILinkMessageAdapter();
        BotWorker worker = new BotWorker(botId, client, adapter, chatService, sceneDetector,
                sessionRepository, this);
        botRegistry.start(botId == null ? "new" : botId, worker);
    }
}