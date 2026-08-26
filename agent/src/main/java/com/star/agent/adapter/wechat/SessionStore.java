package com.star.agent.adapter.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.wechat.ilink.sdk.core.context.ConversationContext;
import com.github.wechat.ilink.sdk.core.context.ContextKey;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.login.LoginContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 登录信息存储 → 文件存储（完整序列化 ResumeContext）
// 关键：resume 必须带 updatesCursor + conversationContexts，否则服务端不认（session expired）。
// 旧版只存 4 个 LoginContext 字段导致 resume 必炸——本版补全，旧格式文件解析失败按无会话处理（转扫码）
// 编解码（toJson/fromJson）抽成静态方法，文件版与数据库版（WechatSessionRepository）共用同一份序列化逻辑
public class SessionStore {

    private static final Path SESSION_DIR = Paths.get("sessions");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    //建立sessions目录，只在最开始执行一次
    static {
        try {
            Files.createDirectories(SESSION_DIR);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建会话目录", e);
        }
    }

    // 完整保存 ResumeContext（login + updatesCursor + 各会话上下文）
    public static void save(String botId, ResumeContext ctx) throws IOException {
        Files.writeString(SESSION_DIR.resolve(botId + ".session"), toJson(ctx));
    }

    // 完整还原 ResumeContext；文件不存在/格式非法（旧版 4 字段格式）→ 返回 null（调用方转扫码）
    public static ResumeContext load(String botId) throws IOException {
        Path file = SESSION_DIR.resolve(botId + ".session");
        if (!Files.exists(file)) return null;
        return fromJson(Files.readString(file));
    }

    // ResumeContext → JSON 字符串（login + cursor + conversations 全字段）
    public static String toJson(ResumeContext ctx) throws IOException {
        LoginContext lc = ctx.getLoginContext();
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode login = root.putObject("login");
        login.put("botToken", lc.getBotToken());
        login.put("userId", lc.getUserId());
        login.put("botId", lc.getBotId());
        login.put("baseUrl", lc.getBaseUrl());
        if (ctx.getUpdatesCursor() != null) {
            root.put("cursor", ctx.getUpdatesCursor());
        }
        ArrayNode convs = root.putArray("conversations");
        for (Map.Entry<String, ConversationContext> e : ctx.getConversationContextMap().entrySet()) {
            ConversationContext cc = e.getValue();
            ObjectNode n = convs.addObject();
            n.put("userId", e.getKey());
            n.put("token", cc.getLatestContextToken());
            if (cc.getTypingTicket() != null) n.put("typing", cc.getTypingTicket());
            if (cc.getSourceMessageId() != null) n.put("msgId", cc.getSourceMessageId());
            if (cc.getSourceMessageTime() != null) n.put("msgTime", cc.getSourceMessageTime());
        }
        return MAPPER.writeValueAsString(root);
    }

    // JSON 字符串 → ResumeContext；缺失 login 字段/格式非法 → null（调用方转扫码）
    public static ResumeContext fromJson(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            return null;   // 旧格式/损坏：视为无会话
        }
        if (root == null || !root.has("login")) {
            return null;
        }
        JsonNode login = root.get("login");
        LoginContext lc = new LoginContext(
                login.get("botToken").asText(),
                login.get("userId").asText(),
                login.get("botId").asText(),
                login.get("baseUrl").asText());
        ResumeContext.Builder b = ResumeContext.builder(lc);
        if (root.has("cursor")) {
            b.updatesCursor(root.get("cursor").asText());
        }
        Map<String, ConversationContext> convs = new HashMap<>();
        for (JsonNode n : root.path("conversations")) {
            String userId = n.get("userId").asText();
            ConversationContext cc = new ConversationContext(new ContextKey(lc.getBotId(), userId));
            cc.updateContextToken(
                    n.get("token").asText(),
                    n.has("msgId") ? n.get("msgId").asLong() : null,
                    n.has("msgTime") ? n.get("msgTime").asLong() : null);
            if (n.has("typing")) {
                cc.setTypingTicket(n.get("typing").asText());
            }
            convs.put(userId, cc);
        }
        if (!convs.isEmpty()) {
            b.conversationContexts(convs);
        }
        return b.build();
    }

    // 列出已保存会话的全部 botId（启动恢复用；多 Bot 版在此基础上扫全部）
    public static List<String> listBotIds() throws IOException {
        try (var stream = Files.list(SESSION_DIR)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".session"))
                    .map(p -> p.getFileName().toString().replace(".session", ""))
                    .sorted()
                    .toList();
        }
    }

    // 最新的会话 botId（按文件修改时间取最新——多份会话文件时用最新的，避免取到被新登录顶掉的旧会话）
    public static String latestBotId() throws IOException {
        try (var stream = Files.list(SESSION_DIR)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".session"))
                    .max(java.util.Comparator.comparingLong(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            return 0L;
                        }
                    }))
                    .map(p -> p.getFileName().toString().replace(".session", ""))
                    .orElse(null);
        }
    }
}