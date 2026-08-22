package com.star.agent.scene;

import com.star.agent.llm.LlmClient;
import com.star.agent.llm.ModelRouter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 场景引擎集成测试：消息 → 三层识别 → 场景 → 断言 模型/工具集/风格 全链路正确
// 覆盖 W5 前 4 天成果的联动（SceneDetector + ScenePolicyConfig + ModelRouter）
@SpringBootTest
class SceneEngineIntegrationTest {

    @Autowired
    private SceneDetector sceneDetector;

    @Autowired
    private ScenePolicyConfig policyConfig;

    @Autowired
    private ModelRouter modelRouter;

    // 创作：识别→CREATION→qwen-max→creative 风格→无工具
    @Test
    void creationMessageRoutesFullChain() {
        Scene scene = sceneDetector.detect("帮我画一只猫");
        assertEquals(Scene.CREATION, scene);

        ScenePolicy policy = policyConfig.getPolicies().get(scene);
        assertEquals("qwen-max", policy.model());
        assertEquals("creative", policy.replyStyle());

        LlmClient client = modelRouter.clientFor(scene);
        assertEquals("qwen-max", client.model());
        assertTrue(policy.tools().isEmpty());
    }

    // 数据：识别→DATA→deepseek-v4-flash→concise→工具白名单含 get_weather
    @Test
    void dataMessageRoutesFullChain() {
        Scene scene = sceneDetector.detect("今天北京天气怎么样");
        assertEquals(Scene.DATA, scene);

        ScenePolicy policy = policyConfig.getPolicies().get(scene);
        assertEquals("deepseek-v4-flash", policy.model());
        assertEquals("concise", policy.replyStyle());
        assertTrue(policy.tools().contains("get_weather"));

        assertEquals("deepseek-v4-flash", modelRouter.clientFor(scene).model());
    }

    // 办公：识别→OFFICE→deepseek-v4-flash→professional→租房三件套白名单
    @Test
    void officeMessageRoutesFullChain() {
        Scene scene = sceneDetector.detect("帮我写周报");
        assertEquals(Scene.OFFICE, scene);

        ScenePolicy policy = policyConfig.getPolicies().get(scene);
        assertEquals("deepseek-v4-flash", policy.model());
        assertEquals("professional", policy.replyStyle());
        assertTrue(policy.tools().containsAll(java.util.List.of("commute_estimate", "house_score", "rent_risk")));
    }

    // 闲聊：识别→CHAT→deepseek-v4-flash→casual→无工具
    @Test
    void chatMessageRoutesFullChain() {
        Scene scene = sceneDetector.detect("你好");
        assertEquals(Scene.CHAT, scene);

        ScenePolicy policy = policyConfig.getPolicies().get(scene);
        assertEquals("deepseek-v4-flash", policy.model());
        assertEquals("casual", policy.replyStyle());
        assertTrue(policy.tools().isEmpty());
    }

    // 群聊：识别→GROUP→deepseek-v4-flash→polite→不记记忆
    @Test
    void groupMessageRoutesFullChain() {
        Scene scene = sceneDetector.detect("@全体成员 下午开会");
        assertEquals(Scene.GROUP, scene);

        ScenePolicy policy = policyConfig.getPolicies().get(scene);
        assertEquals("deepseek-v4-flash", policy.model());
        assertEquals("polite", policy.replyStyle());
        assertEquals("none", policy.memory());
    }

    // 风格切换：5 个场景风格互不相同（答辩叙事：按场景切换回复风格）
    @Test
    void replyStylesDifferAcrossScenes() {
        for (Scene scene : Scene.values()) {
            ScenePolicy policy = policyConfig.getPolicies().get(scene);
            assertTrue(policy.replyStyle() != null && !policy.replyStyle().isBlank(),
                    "场景 " + scene + " 应有回复风格");
        }
        assertEquals(5, java.util.stream.Stream.of(
                policyConfig.getPolicies().get(Scene.CHAT).replyStyle(),
                policyConfig.getPolicies().get(Scene.CREATION).replyStyle(),
                policyConfig.getPolicies().get(Scene.OFFICE).replyStyle(),
                policyConfig.getPolicies().get(Scene.DATA).replyStyle(),
                policyConfig.getPolicies().get(Scene.GROUP).replyStyle()).distinct().count());
    }
}