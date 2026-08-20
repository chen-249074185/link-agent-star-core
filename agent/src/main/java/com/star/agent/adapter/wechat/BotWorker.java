package com.star.agent.adapter.wechat;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.star.agent.message.AgentMessage;
import com.star.agent.scene.Scene;
import com.star.agent.scene.SceneDetector;
import com.star.agent.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
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

        ResumeContext rc = client.exportResumeContext();
        if (rc != null) SessionStore.save(newBotId, rc);
    }

    private void loop() throws Exception {
        System.out.println("开始接收消息...");
        while (running) {
            List<WeixinMessage> msgs = client.getUpdates();
            if (msgs != null && !msgs.isEmpty()) {
                for(WeixinMessage msg : msgs){
                    MDC.put("traceId", UUID.randomUUID().toString().substring(0, 8));  // ① 开工前放钥匙
                    try {
                        AgentMessage am = adapter.convert(msg);
                        Scene scene = sceneDetector.detect(am.text());
                        logger.info("收到消息 来自[{}]: {}，识别场景: {}", am.userId(), am.text(), scene);
                        String reply = chatService.chat(am.text(), am.userId(), scene.name());
                        logger.info("回复[{}]: {}", am.userId(), reply);
                        client.sendText(am.userId(), reply);
                    } finally {
                        MDC.remove("traceId");   // ② 无论成败，收工时拔钥匙
                    }
                }
            }
        }
    }

    public void stop() {
        running = false;
    }
}