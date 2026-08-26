package com.star.agent.adapter.wechat;

import com.star.agent.adapter.BotCommandHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

// 微信聊天命令/自动配置处理：
//   JWT 格式消息（猎聘 Token）→ 自动保存为发送者自己的猎聘 Token（不依赖命令前缀）
//   "删除聊天记录" → 清空发送者自己的历史聊天记录（MySQL 对话+摘要+Redis 瞬时记忆）
//   #newbot → 新增一个 Bot（扫码）；#logout → 登出当前 Bot（删会话）
// 每次实例绑定一个 botId（由 BotWorker 创建时传入），#logout 才知道停哪个
public class WechatBotCommandHandler implements BotCommandHandler {

    private static final Logger logger = LoggerFactory.getLogger(WechatBotCommandHandler.class);

    // 猎聘 Token 是 JWT（三段 base64 点分隔，每段 ≥10 字符）——用格式特征区分"这是 Token"和普通聊天消息
    private static final Pattern JWT_PATTERN = Pattern.compile(
            "^[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}$");

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
        // ① JWT 格式 → 自动配置该用户的猎聘 Token（用户被提示"请发送你的猎聘Token"后直接粘贴即可）
        if (JWT_PATTERN.matcher(cmd).matches()) {
            botManager.saveUserToken(userId, cmd);
            logger.info("用户 {} 已配置猎聘 Token（JWT 自动识别）", userId);
            return "已保存你的猎聘 Token，之后查岗位/简历都走你自己的猎聘账号。"
                    + "（Token 仅本人可用；如需更换，直接再发一次新的即可）";
        }
        // ② 删除聊天记录（自然语言，无命令前缀）：清空发送者自己的 MySQL 历史 + Redis 瞬时记忆
        if (cmd.contains("聊天记录") && (cmd.contains("删除") || cmd.contains("清空"))) {
            botManager.clearChatHistory(userId);
            logger.info("用户 {} 删除聊天记录", userId);
            return "已清空你的聊天记录（对话历史、每日摘要与上下文记忆）";
        }
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