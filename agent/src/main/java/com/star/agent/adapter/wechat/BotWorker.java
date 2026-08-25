package com.star.agent.adapter.wechat;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.star.agent.message.AgentMessage;
import com.star.agent.scene.Scene;
import com.star.agent.scene.SceneDetector;
import com.star.agent.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class BotWorker implements Runnable {

    private final String botId;
    private ILinkClient client;
    private volatile boolean running = true;
    private final ILinkMessageAdapter adapter;
    private final ChatService chatService;// 真对话入口：收消息→调 chat()→回复
    private final SceneDetector sceneDetector;
    private static final Logger logger = LoggerFactory.getLogger(BotWorker.class);

    public BotWorker(String botId, ILinkClient client, ILinkMessageAdapter adapter, ChatService chatService, SceneDetector sceneDetector) {
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
            loop();
        } catch (Exception e) {
            e.printStackTrace();
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

    private void loop() throws Exception {
        System.out.println("开始接收消息...");
        while (running) {
            AgentMessage am = adapter.receive();   // 收消息统一走 adapter（内部 getUpdates+缓冲）
            if (am == null) {
                continue;
            }
            MDC.put("traceId", UUID.randomUUID().toString().substring(0, 8));  // ① 开工前放钥匙
            try {
                Scene scene = sceneDetector.detect(am.text());
                logger.info("收到消息 来自[{}]: {}，识别场景: {}", am.userId(), am.text(), scene);
                String reply = chatService.chat(am.text(), am.userId(), scene.name());
                logger.info("回复[{}]: {}", am.userId(), reply);
                adapter.send(am.userId(), reply);   // 发送也走 adapter，不再直接碰 SDK
            } finally {
                MDC.remove("traceId");   // ② 无论成败，收工时拔钥匙
            }
        }
    }

    public void stop() {
        running = false;
    }
}