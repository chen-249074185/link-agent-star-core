package com.star.agent.adapter.wechat;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.star.agent.governance.GovernanceKernel;
import com.star.agent.governance.InMemoryConfirmationStore;
import com.star.agent.governance.InMemoryRateLimiter;
import com.star.agent.infra.config.AppConfig;
import com.star.agent.llm.ModelRouter;
import com.star.agent.llm.client.DashScopeLlmClient;
import com.star.agent.llm.client.DeepSeekLlmClient;
import com.star.agent.scene.SceneDetector;
import com.star.agent.service.ChatService;
import com.star.agent.service.RentRequestDetector;
import com.star.agent.skill.SkillRegistry;
import com.star.agent.skill.impl.CommuteSkill;
import com.star.agent.skill.impl.HouseScoreSkill;
import com.star.agent.skill.impl.RentRiskSkill;
import com.star.agent.skill.impl.WeatherSkill;
import com.star.agent.skill.impl.mock.CrawlSkill;
import com.star.agent.skill.impl.mock.LiepinSkill;
import com.star.agent.skill.impl.mock.RagSearchSkill;
import com.star.agent.skill.impl.mock.UuDeliverySkill;
import com.star.agent.workflow.RentDecisionWorkflow;
import com.star.agent.workflow.base.WorkflowEngine;

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
        SkillRegistry skillRegistry = new SkillRegistry(List.of(weather));
        InMemoryConfirmationStore confirmStore = new InMemoryConfirmationStore();
        InMemoryRateLimiter rateLimiter = new InMemoryRateLimiter();
        GovernanceKernel kernel = new GovernanceKernel(skillRegistry, confirmStore, rateLimiter);
        DeepSeekLlmClient llm = new DeepSeekLlmClient(config);
        DashScopeLlmClient dashScope = new DashScopeLlmClient(config);   // qwen-turbo（分类/闲聊流式）
        DashScopeLlmClient qwenMax = new DashScopeLlmClient("qwen-max", config);   // 创作流式
        ModelRouter modelRouter = new ModelRouter(llm, dashScope, qwenMax, null);
        // 工作流链路：Mock 技能 + 真实技能（扫码验证场景也能跑租房工作流）
        WorkflowEngine engine = new WorkflowEngine();
        SkillRegistry workflowSkills = new SkillRegistry(List.of(
                new RagSearchSkill(), new CrawlSkill(), new LiepinSkill(), new UuDeliverySkill(),
                new CommuteSkill(config),   // 走 AppConfig 构造器 = 内部默认 baseUrl（含 /v3/direction/driving 路径）
                new HouseScoreSkill(), new RentRiskSkill()));
        RentDecisionWorkflow workflow = new RentDecisionWorkflow(workflowSkills, engine);
        ChatService chatService = new ChatService(llm, modelRouter, skillRegistry, kernel, new RentRequestDetector(), workflow,
                null, null, null, null);   // 手动链路不进 Spring：记忆/压缩全部降级关闭

        BotWorker worker = new BotWorker(null, client, new ILinkMessageAdapter(), chatService, new SceneDetector());
        Thread thread = new Thread(worker, "bot-new");
        thread.setDaemon(true);
        thread.start();

        Runtime.getRuntime().addShutdownHook(new Thread(worker::stop));
        System.out.println("机器人已启动，在后台运行");

        Thread.currentThread().join();
    }
}