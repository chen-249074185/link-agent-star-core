package com.star.agent.adapter.wechat;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class BotWorker implements Runnable {

    private final String botId;
    private final ILinkClient client;
    private volatile boolean running = true;

    public BotWorker(String botId, ILinkClient client) {
        this.botId = botId;
        this.client = client;
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
                for (WeixinMessage msg : msgs) {
                    String userId = msg.getFrom_user_id();
                    StringBuilder text = new StringBuilder();
                    if (msg.getItem_list() != null) {
                        for (var item : msg.getItem_list()) {
                            if (item.getText_item() != null) {
                                text.append(item.getText_item().getText());
                            }
                        }
                    }
                    System.out.println("收到消息 来自[" + userId + "]: " + text);
                }
            }
        }
    }

    public void stop() {
        running = false;
    }
}