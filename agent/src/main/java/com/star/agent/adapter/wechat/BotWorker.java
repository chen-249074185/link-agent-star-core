package com.star.agent.adapter.wechat;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.star.agent.adapter.MessageDispatcher;
import com.star.agent.adapter.ReconnectHandler;
import com.star.agent.scene.SceneDetector;
import com.star.agent.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

// 机器人生命周期：登录/恢复 → 启动双线程 MessageDispatcher（收消息与回复解耦）
// 断线自动重登：onDisconnected 回调 = 强制扫码重登（resume 会话已失效时不能再用同一份过期会话，
// SDK resume 自报 LOGGED_IN 但服务端已过期，第一次 getUpdates 才暴露 session expired → 重登必须跳过 resume）
public class BotWorker implements Runnable, ReconnectHandler {

    private static final Logger logger = LoggerFactory.getLogger(BotWorker.class);

    private final String botId;
    private ILinkClient client;
    private volatile boolean running = true;
    private volatile boolean forceScan = false;   // 重登强制扫码：跳过 resume（过期会话死循环的解法）
    private final ILinkMessageAdapter adapter;
    private final ChatService chatService;
    private final SceneDetector sceneDetector;
    private final Object loginLock = new Object();   // 首次登录与断线重登可能不同线程，防并发重入
    private MessageDispatcher dispatcher;

    public BotWorker(String botId, ILinkClient client, ILinkMessageAdapter adapter,
                     ChatService chatService, SceneDetector sceneDetector) {
        this.botId = botId;
        this.client = client;
        this.adapter = adapter;
        this.chatService = chatService;
        this.sceneDetector = sceneDetector;
    }

    @Override
    public void run() {
        try {
            login();
            dispatcher = new MessageDispatcher(adapter, sceneDetector, chatService, this);
            dispatcher.start();
        } catch (Exception e) {
            logger.error("机器人启动失败", e);
        }
    }

    // 断线自动重登（MessageDispatcher 的 ReconnectHandler 回调，在 fetcher 线程执行）
    // 收消息失败 = 当前会话已失效（如 session expired）→ 强制扫码，不再用过期会话 resume
    @Override
    public void onDisconnected() throws Exception {
        synchronized (loginLock) {
            forceScan = true;
            try {
                login();
            } finally {
                forceScan = false;
            }
        }
    }

    private void login() throws Exception {
        // 首次启动允许 resume（会话文件可能仍有效）；重登（forceScan）跳过 resume 直接扫码
        if (!forceScan && botId != null) {
            ResumeContext saved = SessionStore.load(botId);
            if (saved != null) {
                ILinkClient resumeClient = ILinkClient.builder()
                        .config(ILinkConfigFactory.create())
                        .resumeContext(saved)
                        .build();
                if ("LOGGED_IN".equals(resumeClient.getConnectionStatus().name())) {
                    this.client = resumeClient;
                    adapter.attach(resumeClient);   // 恢复登录换了 client，必须重新绑定（坑7 同类）
                    System.out.println("会话恢复成功，免扫码登录");
                    return;
                }
            }
        }

        System.out.println("需要扫码登录");
        String qr = client.executeLogin();
        String qrUrl = "https://api.qrserver.com/v1/create-qr-code/?size=300x300&data="
                + URLEncoder.encode(qr, StandardCharsets.UTF_8);
        System.out.println("扫码登录: " + qrUrl);
        LoginContext ctx = client.getLoginFuture().get();
        String newBotId = ctx.getBotId();
        System.out.println("登录完成，botId = " + newBotId);
        adapter.attach(client);   // 扫码登录完成，绑定 adapter

        ResumeContext rc = client.exportResumeContext();
        if (rc != null) SessionStore.save(newBotId, rc);
    }

    public void stop() {
        running = false;
        if (dispatcher != null) {
            dispatcher.stop();
        }
    }
}