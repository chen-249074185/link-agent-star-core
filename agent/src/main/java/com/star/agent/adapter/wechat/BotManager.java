package com.star.agent.adapter.wechat;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
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
    private final MultimediaHandler multimediaHandler;
    private final DebounceProcessor debounce;
    private final UserConfigRepository userConfigRepository;
    private final ConversationDao conversationDao;
    private final DailySummaryDao dailySummaryDao;
    private final EphemeralMemory ephemeralMemory;
    private final ImageFetcher imageFetcher;
    private final WechatIdentityResolver identityResolver;   // 微信身份→账号映射（10-31 绑定互通）

    public BotManager(ChatService chatService, SceneDetector sceneDetector,
                      WechatSessionRepository sessionRepository, BotRegistry botRegistry,
                      MultimediaHandler multimediaHandler, UserConfigRepository userConfigRepository,
                      DebounceProcessor debounce, ConversationDao conversationDao,
                      DailySummaryDao dailySummaryDao, EphemeralMemory ephemeralMemory,
                      ImageFetcher imageFetcher, WechatIdentityResolver identityResolver) {
        this.chatService = chatService;
        this.sceneDetector = sceneDetector;
        this.sessionRepository = sessionRepository;
        this.botRegistry = botRegistry;
        this.multimediaHandler = multimediaHandler;
        this.userConfigRepository = userConfigRepository;
        this.debounce = debounce;
        this.conversationDao = conversationDao;
        this.dailySummaryDao = dailySummaryDao;
        this.ephemeralMemory = ephemeralMemory;
        this.imageFetcher = imageFetcher;
        this.identityResolver = identityResolver;
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

    // 保存某用户的猎聘 Token（#token/JWT 自动识别）：只写该用户自己的配置行
    public void saveUserToken(String userId, String token) {
        userConfigRepository.saveLiepinToken(userId, token);
    }

    // 取某用户的猎聘 Token（McpSkill 注入用）；未配置返回 null
    public String findUserToken(String userId) {
        return userConfigRepository.findLiepinToken(userId);
    }

    // 清空某用户的聊天记录（"删除聊天记录"命令）：MySQL 短期记忆 + 每日摘要 + Redis 瞬时记忆三处全清
    public void clearChatHistory(String userId) {
        int conversations = conversationDao.deleteByUserId(userId);
        int summaries = dailySummaryDao.deleteByUserId(userId);
        ephemeralMemory.delete(userId);
        logger.info("用户 {} 聊天记录已清空：对话 {} 条、每日摘要 {} 条、瞬时记忆已删", userId, conversations, summaries);
    }

    // 扫码确认后启动 Bot（10-31：Web 扫码只是官方侧登录，应用还得把会话拉上线，否则手机 Bot 不可用）
    // 用确认返回的登录上下文（botToken/botId/userId/baseUrl）存会话仓库 → BotWorker.login 恢复直接生效；
    // 同 botId 已在跑 = 顶号（换宿主），先停旧的再起
    public void startFromScan(String botToken, String userId, String botId, String baseUrl) {
        if (botId == null || botId.isBlank()) {
            logger.warn("扫码确认缺少 botId，无法启动 Bot");
            return;
        }
        if (botRegistry.isRunning(botId)) {
            logger.info("Bot {} 已在运行，扫码确认后顶号重启", botId);
            botRegistry.stop(botId);
        }
        LoginContext lc = new LoginContext(botToken, userId, botId, baseUrl);
        sessionRepository.save(ResumeContext.of(lc));   // 先存，BotWorker.login 的 resume 分支直接恢复
        logger.info("扫码确认保存会话并启动 Bot: botId={}", botId);
        startBot(botId);
    }

    private void startBot(String botId) {
        ILinkClient client = ILinkClient.builder()
                .config(ILinkConfigFactory.create())
                .build();
        ILinkMessageAdapter adapter = new ILinkMessageAdapter();
        BotWorker worker = new BotWorker(botId, client, adapter, chatService, sceneDetector,
                sessionRepository, this, multimediaHandler, debounce, new ReplySender(imageFetcher),
                identityResolver);
        botRegistry.start(botId == null ? "new" : botId, worker);
    }
}