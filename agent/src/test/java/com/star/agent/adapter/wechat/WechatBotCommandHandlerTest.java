package com.star.agent.adapter.wechat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// 微信聊天命令单测：#newbot 新增 / #logout 登出（带 botId）/ 普通消息放行
class WechatBotCommandHandlerTest {

    private final BotManager botManager = mock(BotManager.class);

    @Test
    void newbot_launchesNewBot() {
        WechatBotCommandHandler handler = new WechatBotCommandHandler("bot-1", botManager);

        String reply = handler.handle("#newbot", "u1");

        assertTrue(reply.contains("新机器人"), "应提示已发起新 Bot 登录");
        verify(botManager).launchNew();
    }

    @Test
    void logout_stopsAndDeletesCurrentBot() {
        WechatBotCommandHandler handler = new WechatBotCommandHandler("bot-1", botManager);

        String reply = handler.handle("#logout", "u1");

        assertTrue(reply.contains("已登出"), "应提示已登出");
        verify(botManager).logout("bot-1");
    }

    // 未绑定 botId 的实例（新扫码中的 bot）登出无意义 → 提示但不操作
    @Test
    void logout_withoutBotId_noop() {
        WechatBotCommandHandler handler = new WechatBotCommandHandler(null, botManager);

        String reply = handler.handle("#logout", "u1");

        assertTrue(reply.contains("未绑定"), "应提示未绑定机器人");
        verify(botManager, never()).logout(org.mockito.ArgumentMatchers.anyString());
    }

    // 普通消息不是命令 → null（放行进正常对话）
    @Test
    void normalMessage_returnsNull() {
        WechatBotCommandHandler handler = new WechatBotCommandHandler("bot-1", botManager);

        assertNull(handler.handle("你好", "u1"));
        assertNull(handler.handle("#abc", "u1"), "未识别的 # 开头词不是命令");
        verify(botManager, never()).launchNew();
    }

    // 用户直接发猎聘 Token（JWT 格式）→ 自动保存到发送者自己的配置（不依赖命令前缀）
    @Test
    void jwtMessage_autoSavesToken() {
        WechatBotCommandHandler handler = new WechatBotCommandHandler("bot-1", botManager);
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.dGhpc2lzYXRlc3R0b2tlbnN0cmluZzEyMzQ1Ng";

        String reply = handler.handle(jwt, "wx_u1");

        assertTrue(reply.contains("已保存你的猎聘 Token"), "应确认保存");
        verify(botManager).saveUserToken("wx_u1", jwt);   // 存发送者自己的 userId
    }

    // 普通中文消息不是 JWT → 放行（格式特征区分，不误判）
    @Test
    void nonJwtMessage_notSaved() {
        WechatBotCommandHandler handler = new WechatBotCommandHandler("bot-1", botManager);

        assertNull(handler.handle("今天天气怎么样", "u1"));
        assertNull(handler.handle("帮我看看我的简历", "u1"));
        verify(botManager, never()).saveUserToken(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    // "删除聊天记录"（自然语言，无命令前缀）→ 清空发送者自己的历史聊天记录
    @Test
    void deleteChatHistory_clearsUserHistory() {
        WechatBotCommandHandler handler = new WechatBotCommandHandler("bot-1", botManager);

        String reply = handler.handle("删除聊天记录", "wx_u1");

        assertTrue(reply.contains("已清空"), "应提示已清空");
        verify(botManager).clearChatHistory("wx_u1");   // 清发送者自己的 userId
    }

    // "清空聊天记录"变体同样触发
    @Test
    void deleteChatHistory_variantTriggers() {
        WechatBotCommandHandler handler = new WechatBotCommandHandler("bot-1", botManager);

        String reply = handler.handle("帮我清空一下聊天记录", "u1");

        assertTrue(reply.contains("已清空"), "清空变体也应触发");
        verify(botManager).clearChatHistory("u1");
    }

    // 普通消息不含触发词 → 放行不误删
    @Test
    void deleteChatHistory_notTriggeredByOrdinaryMessages() {
        WechatBotCommandHandler handler = new WechatBotCommandHandler("bot-1", botManager);

        assertNull(handler.handle("聊天记录里说了什么", "u1"), "只问不删 → 放行");
        assertNull(handler.handle("把昨天的对话删了", "u1"), "未说'聊天记录' → 放行");
        verify(botManager, never()).clearChatHistory(org.mockito.ArgumentMatchers.anyString());
    }
}