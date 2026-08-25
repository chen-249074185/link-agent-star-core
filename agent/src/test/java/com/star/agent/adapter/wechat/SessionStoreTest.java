package com.star.agent.adapter.wechat;

import com.github.wechat.ilink.sdk.core.context.ConversationContext;
import com.github.wechat.ilink.sdk.core.context.ContextKey;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// SessionStore 完整序列化测试：login+cursor+conversations 全字段 round-trip
// 关键回归：旧版只存 4 字段导致 resume 后服务端 session expired（getUpdates 不认）
class SessionStoreTest {

    private static final Path SESSION_DIR = Path.of("sessions");

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(SESSION_DIR);
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(SESSION_DIR.resolve("test-bot-aaa.session"));
        Files.deleteIfExists(SESSION_DIR.resolve("test-bot-bbb.session"));
    }

    // 造一个带 cursor + 会话上下文的完整 ResumeContext（模拟 exportResumeContext 的产物）
    private ResumeContext fullContext() {
        LoginContext lc = new LoginContext("token1", "user1", "test-bot-aaa", "https://x");
        ConversationContext cc = new ConversationContext(new ContextKey("test-bot-aaa", "wx_u1"));
        cc.updateContextToken("ctx-token-1", 100L, 200L);
        cc.setTypingTicket("typing-1");
        return ResumeContext.builder(lc)
                .updatesCursor("cursor-abc")
                .conversationContexts(Map.of("wx_u1", cc))
                .build();
    }

    // 完整 round-trip：cursor 和会话上下文不能丢（这就是 resume 能用的关键）
    @Test
    void saveLoad_fullRoundTrip() throws Exception {
        SessionStore.save("test-bot-aaa", fullContext());

        ResumeContext ctx = SessionStore.load("test-bot-aaa");

        assertNotNull(ctx, "应能还原会话");
        LoginContext lc = ctx.getLoginContext();
        assertEquals("token1", lc.getBotToken());
        assertEquals("user1", lc.getUserId());
        assertEquals("test-bot-aaa", lc.getBotId());
        assertEquals("cursor-abc", ctx.getUpdatesCursor(), "消息游标必须还原（缺失会导致 session expired）");
        ConversationContext cc = ctx.getConversationContextMap().get("wx_u1");
        assertNotNull(cc, "会话上下文必须还原");
        assertEquals("ctx-token-1", cc.getLatestContextToken());
        assertEquals(100L, cc.getSourceMessageId());
        assertEquals("typing-1", cc.getTypingTicket());
    }

    // 旧格式（4 字段 | 分隔）无法解析 → 返回 null（调用方转扫码，不崩）
    @Test
    void load_oldFormat_returnsNull() throws Exception {
        Files.writeString(SESSION_DIR.resolve("test-bot-aaa.session"), "token1|user1|test-bot-aaa|https://x");

        assertNull(SessionStore.load("test-bot-aaa"), "旧格式应视为无会话（转扫码）");
    }

    @Test
    void load_missingFile_returnsNull() throws Exception {
        assertNull(SessionStore.load("no-such-bot"));
    }

    @Test
    void listBotIds_findsSavedSessions() throws Exception {
        Files.writeString(SESSION_DIR.resolve("test-bot-aaa.session"), "{}");
        Files.writeString(SESSION_DIR.resolve("test-bot-bbb.session"), "{}");

        List<String> ids = SessionStore.listBotIds();

        assertTrue(ids.contains("test-bot-aaa"));
        assertTrue(ids.contains("test-bot-bbb"));
    }

    @Test
    void latestBotId_returnsNewest() throws Exception {
        Path old = SESSION_DIR.resolve("test-bot-aaa.session");
        Path newer = SESSION_DIR.resolve("test-bot-bbb.session");
        Files.writeString(old, "{}");
        Thread.sleep(10);
        Files.writeString(newer, "{}");   // 修改时间更新

        assertEquals("test-bot-bbb", SessionStore.latestBotId(), "应取最新会话（避免被顶掉的旧会话）");
    }
}