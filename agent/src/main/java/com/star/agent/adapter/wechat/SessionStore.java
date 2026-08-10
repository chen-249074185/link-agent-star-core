package com.star.agent.adapter.wechat;

import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.login.LoginContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

//登录信息存储 --> 文件存储
public class SessionStore {

    private static final Path SESSION_DIR = Paths.get("sessions");

    //建立sessions目录，只在最开始执行一次
    static {
        try {
            Files.createDirectories(SESSION_DIR);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建会话目录", e);
        }
    }

    //得到loginContext，并用“|”分隔写入文件
    //拆resumeContext存
    public static void save(String botId, ResumeContext ctx) throws IOException {
        LoginContext lc = ctx.getLoginContext();
        String content = String.join("|",
                lc.getBotToken(), lc.getUserId(), lc.getBotId(), lc.getBaseUrl());
        Files.writeString(SESSION_DIR.resolve(botId + ".session"), content);
    }

    //split("\\|")：转义
    //组成resumeContext形式
    public static ResumeContext load(String botId) throws IOException {
        Path file = SESSION_DIR.resolve(botId + ".session");
        if (!Files.exists(file)) return null;
        String[] parts = Files.readString(file).split("\\|");
        LoginContext lc = new LoginContext(parts[0], parts[1], parts[2], parts[3]);
        return ResumeContext.of(lc);
    }
}