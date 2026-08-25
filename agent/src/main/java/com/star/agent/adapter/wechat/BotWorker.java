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
// 断线自动重登：onDisconnected 回调 = 重走 login()（恢复失败转扫码），adapter 换绑后继续收
public class BotWorker implements Runnable, ReconnectHandler {

    private static final Logger logger = LoggerFactory.getLogger(BotWorker.class);

    private final String botId;
    private ILinkClient client;
    private volatile boolean running = true;
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
    @Override
    public void onDisconnected() throws Exception {
        synchronized (loginLock) {
            login();
        }
    }

    private void login() throws Exception {
        if (botId != null) {
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