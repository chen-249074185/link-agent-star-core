package com.star.agent.adapter.wechat;

import com.github.wechat.ilink.sdk.ILinkClient;

public class ILinkLoginVerifier {

    public static void main(String[] args) throws InterruptedException {
        ILinkClient client = ILinkClient.builder()
                .config(ILinkConfigFactory.create())
                .build();

        BotWorker worker = new BotWorker(null, client);
        Thread thread = new Thread(worker, "bot-new");
        thread.setDaemon(true);
        thread.start();

        Runtime.getRuntime().addShutdownHook(new Thread(worker::stop));
        System.out.println("机器人已启动，在后台运行");

        Thread.currentThread().join();
    }
}