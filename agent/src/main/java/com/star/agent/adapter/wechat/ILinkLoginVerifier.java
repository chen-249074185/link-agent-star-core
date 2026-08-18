package com.star.agent.adapter.wechat;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.star.agent.infra.config.AppConfig;
import com.star.agent.llm.client.DeepSeekLlmClient;
import com.star.agent.scene.SceneDetector;
import com.star.agent.service.ChatService;
import com.star.agent.skill.WeatherSkill;
import com.star.agent.tool.ToolDispatcher;

import java.util.List;

public class ILinkLoginVerifier {

    public static void main(String[] args) throws InterruptedException {
        ILinkClient client = ILinkClient.builder()
                .config(ILinkConfigFactory.create())
                .build();

        // 手动搭 ChatService 链路（没跑 Spring 容器，只能手动 new）
        // 原则 = 和 Spring 装配一致的构造注入，只是自己写一遍
        AppConfig config = new AppConfig();
        WeatherSkill weather = new WeatherSkill(config);
        ToolDispatcher dispatcher = new ToolDispatcher(List.of(weather));
        DeepSeekLlmClient llm = new DeepSeekLlmClient(config);
        ChatService chatService = new ChatService(llm, dispatcher, List.of(weather));

        BotWorker worker = new BotWorker(null, client, new ILinkMessageAdapter(), chatService,new SceneDetector());
        Thread thread = new Thread(worker, "bot-new");
        thread.setDaemon(true);
        thread.start();

        Runtime.getRuntime().addShutdownHook(new Thread(worker::stop));
        System.out.println("机器人已启动，在后台运行");

        Thread.currentThread().join();
    }
}