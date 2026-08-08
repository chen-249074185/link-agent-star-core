package com.star.agent.adapter.wechat;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.login.LoginContext;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class ILinkLoginVerifier {

    public static void main(String[] args) throws Exception{
        ILinkClient client = ILinkClient.builder()
                .config(ILinkConfigFactory.create())
                .build();

        //发出二维码申请
        String qr = client.executeLogin();
        String qrUrl = "https://api.qrserver.com/v1/create-qr-code/?size=300x300&data="
                + URLEncoder.encode(qr, StandardCharsets.UTF_8);
        System.out.println("请用微信扫描二维码登录: " + qrUrl);

        //同步阻塞
        LoginContext ctx = client.getLoginFuture().get();
        String botId = ctx.getBotId();
        System.out.println("登录完成，botId = " + botId);
    }
}
