package com.star.agent.adapter.wechat;

import com.star.agent.adapter.BotCommandHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// 微信聊天命令处理：#newbot 新增一个 Bot（扫码），#logout 登出当前 Bot（删会话）
// 每次实例绑定一个 botId（由 BotWorker 创建时传入），#logout 才知道停哪个
public class WechatBotCommandHandler implements BotCommandHandler {

    private static final Logger logger = LoggerFactory.getLogger(WechatBotCommandHandler.class);

    private final String botId;
    private final BotManager botManager;

    public WechatBotCommandHandler(String botId, BotManager botManager) {
        this.botId = botId;
        this.botManager = botManager;
    }

    @Override
    public String handle(String text, String userId) {
        if (text == null) {
            return null;
        }
        String cmd = text.trim();
        if (cmd.startsWith("#newbot")) {
            botManager.launchNew();
            return "已发起新机器人登录，二维码请查看服务器控制台";
        }
        if (cmd.startsWith("#logout")) {
            if (botId == null) {
                return "当前会话未绑定机器人，无法登出";
            }
            botManager.logout(botId);
            logger.info("用户 {} 触发 #logout，登出 bot {}", userId, botId);
            return "已登出（会话已删除），下次启动需重新扫码";
        }
        return null;
    }
}