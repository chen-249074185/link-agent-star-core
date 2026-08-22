package com.star.agent.llm;

import com.star.agent.scene.Scene;
import com.star.agent.scene.ScenePolicy;
import com.star.agent.scene.ScenePolicyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

// 多模型路由器：Scene → ScenePolicy.model → LlmClient 实现
// 查不到配置/客户端就回退 DeepSeek（对话大脑兜底，绝不空指针）
@Component
public class ModelRouter {

    private static final Logger logger = LoggerFactory.getLogger(ModelRouter.class);

    // 模型名 → 客户端 的路由表（模型名以 ScenePolicy 配置为准）
    private final Map<String, LlmClient> clients;

    private final ScenePolicyConfig scenePolicyConfig;
    private final LlmClient fallback;

    public ModelRouter(@Qualifier("deepSeekLlmClient") LlmClient deepSeekClient,
                       @Qualifier("dashScopeLlmClient") LlmClient dashScopeTurboClient,
                       @Qualifier("qwenMaxClient") LlmClient qwenMaxClient,
                       ScenePolicyConfig scenePolicyConfig) {
        this.clients = new LinkedHashMap<>();
        this.clients.put(deepSeekClient.model(), deepSeekClient);
        this.clients.put(dashScopeTurboClient.model(), dashScopeTurboClient);
        this.clients.put(qwenMaxClient.model(), qwenMaxClient);
        this.fallback = deepSeekClient;
        this.scenePolicyConfig = scenePolicyConfig;
    }

    // 按场景选客户端：查 ScenePolicy 的 model 名 → 查路由表 → 查不到回退 DeepSeek
    public LlmClient clientFor(Scene scene) {
        if (scenePolicyConfig != null) {   // 手动链路（纯 main）可能没有配置表，直接回退
            ScenePolicy policy = scenePolicyConfig.getPolicies().get(scene);
            if (policy != null) {
                LlmClient client = clients.get(policy.model());
                if (client != null) {
                    return client;
                }
                logger.warn("场景 {} 配置的模型 {} 无对应客户端，回退 DeepSeek", scene, policy.model());
            }
        }
        return fallback;
    }
}