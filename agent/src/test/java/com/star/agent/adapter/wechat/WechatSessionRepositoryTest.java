package com.star.agent.adapter.wechat;

import com.github.wechat.ilink.sdk.core.context.ConversationContext;
import com.github.wechat.ilink.sdk.core.context.ContextKey;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 微信会话仓库（数据库版）集成测试：真实 MySQL wechat_session 表
// 关键回归：resume 必须带完整 ResumeContext（cursor + 会话上下文），缺失会导致服务端 session expired
@SpringBootTest
class WechatSessionRepositoryTest {

    @Autowired
    WechatSessionRepository repository;

    @AfterEach
    void tearDown() {
        repository.delete("repo-bot-aaa");
        repository.delete("repo-bot-bbb");
        repository.delete("repo-bot-ccc");
    }

    // 造一个带 cursor + 会话上下文的完整 ResumeContext（模拟 exportResumeContext 的产物）
    private ResumeContext fullContext(String botId) {
        LoginContext lc = new LoginContext("token-1", "user-1", botId, "https://x");
        ConversationContext cc = new ConversationContext(new ContextKey(botId, "wx_u1"));
        cc.updateContextToken("ctx-token-1", 100L, 200L);
        cc.setTypingTicket("typing-1");
        return ResumeContext.builder(lc)
                .updatesCursor("cursor-abc")
                .conversationContexts(Map.of("wx_u1", cc))
                .build();
    }

    // 完整 round-trip：cursor 和会话上下文不能丢（这就是 resume 能用的关键）
    @Test
    void saveLoad_fullRoundTrip() {
        repository.save(fullContext("repo-bot-aaa"));

        ResumeContext ctx = repository.load("repo-bot-aaa");

        assertNotNull(ctx, "应能还原会话");
        LoginContext lc = ctx.getLoginContext();
        assertEquals("token-1", lc.getBotToken());
        assertEquals("user-1", lc.getUserId());
        assertEquals("repo-bot-aaa", lc.getBotId());
        assertEquals("cursor-abc", ctx.getUpdatesCursor(), "消息游标必须还原（缺失会导致 session expired）");
        ConversationContext cc = ctx.getConversationContextMap().get("wx_u1");
        assertNotNull(cc, "会话上下文必须还原");
        assertEquals("ctx-token-1", cc.getLatestContextToken());
        assertEquals(100L, cc.getSourceMessageId());
        assertEquals("typing-1", cc.getTypingTicket());
    }

    // 同 bot 重复登录=顶号覆盖：库内只留一行，load 取最新
    @Test
    void save_duplicateBotId_overwrites() {
        repository.save(fullContext("repo-bot-bbb"));

        LoginContext lc2 = new LoginContext("token-2", "user-2", "repo-bot-bbb", "https://x");
        repository.save(ResumeContext.builder(lc2).updatesCursor("cursor-2").build());

        ResumeContext ctx = repository.load("repo-bot-bbb");
        assertEquals("token-2", ctx.getLoginContext().getBotToken(), "同 bot 重复保存应顶号覆盖为最新");
        assertEquals("cursor-2", ctx.getUpdatesCursor());
    }

    @Test
    void load_missingBot_returnsNull() {
        assertNull(repository.load("no-such-bot"));
    }

    // 启动扫表多 Bot 恢复的数据源（表里可能有真实 bot 会话，只断言自己的测试数据在列）
    @Test
    void findAllBotIds_returnsAll() {
        repository.save(fullContext("repo-bot-aaa"));
        repository.save(fullContext("repo-bot-bbb"));

        List<String> ids = repository.findAllBotIds();

        assertTrue(ids.contains("repo-bot-aaa"), "测试数据应被扫到");
        assertTrue(ids.contains("repo-bot-bbb"), "测试数据应被扫到");
    }

    @Test
    void delete_removesSession() {
        repository.save(fullContext("repo-bot-ccc"));

        repository.delete("repo-bot-ccc");

        assertNull(repository.load("repo-bot-ccc"), "删除后不应能恢复");
        assertFalse(repository.findAllBotIds().contains("repo-bot-ccc"), "删除后不应再被扫到");
    }

    // 过期清理：造一条 saved_at 8 天前的旧会话 → 删 7 天前的只删旧不删新
    @Test
    void deleteOlderThan_removesOnlyOld() {
        repository.save(fullContext("repo-bot-aaa"));   // 新会话（now）
        repository.save(fullContext("repo-bot-bbb"));   // 待改旧
        repository.updateSavedAt("repo-bot-bbb", LocalDateTime.now().minusDays(8));

        int deleted = repository.deleteOlderThan(7);

        assertEquals(1, deleted, "只应删掉 8 天前的旧会话");
        assertNotNull(repository.load("repo-bot-aaa"), "新会话不应被删");
        assertNull(repository.load("repo-bot-bbb"), "旧会话应被清理");
    }
}