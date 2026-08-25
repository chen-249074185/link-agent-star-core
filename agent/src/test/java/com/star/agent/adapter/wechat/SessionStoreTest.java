package com.star.agent.adapter.wechat;

import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// SessionStore.listBotIds：列出已保存会话的 botId（启动恢复用，W11 扫表的雏形）
// SessionStore 是静态固定目录（sessions/，已 gitignore），测试写临时假会话文件，测完清理
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

    @Test
    void listBotIds_findsSavedSessions() throws Exception {
        // 直接写两个假会话文件（内容格式：botToken|userId|botId|baseUrl）
        Files.writeString(SESSION_DIR.resolve("test-bot-aaa.session"), "token1|user1|test-bot-aaa|https://x");
        Files.writeString(SESSION_DIR.resolve("test-bot-bbb.session"), "token2|user2|test-bot-bbb|https://x");

        List<String> ids = SessionStore.listBotIds();

        assertTrue(ids.contains("test-bot-aaa"), "应列出测试会话 aaa");
        assertTrue(ids.contains("test-bot-bbb"), "应列出测试会话 bbb");
    }

    @Test
    void load_restoresLoginContext() throws Exception {
        Files.writeString(SESSION_DIR.resolve("test-bot-aaa.session"), "token1|user1|test-bot-aaa|https://x");

        ResumeContext ctx = SessionStore.load("test-bot-aaa");

        LoginContext lc = ctx.getLoginContext();
        assertEquals("token1", lc.getBotToken());
        assertEquals("user1", lc.getUserId());
        assertEquals("test-bot-aaa", lc.getBotId());
    }
}