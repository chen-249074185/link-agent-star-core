package com.star.agent.scene;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

// 场景策略配置绑定单测：@SpringBootTest 起全上下文，验证 yml 绑定 + 启动校验
@SpringBootTest
class ScenePolicyConfigTest {

    @Autowired
    private ScenePolicyConfig config;

    // 5 个场景全部有策略
    @Test
    void allFiveScenesConfigured() {
        assertEquals(5, config.getPolicies().size());
        for (Scene scene : Scene.values()) {
            assertNotNull(config.getPolicies().get(scene), "场景 " + scene + " 应有策略");
        }
    }

    // DATA 场景：允许 get_weather + 瞬时记忆
    @Test
    void dataSceneAllowsWeatherTool() {
        ScenePolicy data = config.getPolicies().get(Scene.DATA);
        assertTrue(data.tools().contains("get_weather"));
        assertEquals("ephemeral", data.memory());
        assertEquals("concise", data.replyStyle());
    }

    // GROUP 场景：不记记忆、不调工具（群聊安全）
    @Test
    void groupSceneNoMemoryNoTools() {
        ScenePolicy group = config.getPolicies().get(Scene.GROUP);
        assertTrue(group.tools().isEmpty());
        assertEquals("none", group.memory());
    }

    // OFFICE 场景：租房三件套进白名单
    @Test
    void officeSceneHasRentTools() {
        ScenePolicy office = config.getPolicies().get(Scene.OFFICE);
        assertTrue(office.tools().containsAll(java.util.List.of("commute_estimate", "house_score", "rent_risk")));
    }

    // CREATION 场景：用创作模型
    @Test
    void creationSceneUsesQwenMax() {
        assertEquals("qwen-max", config.getPolicies().get(Scene.CREATION).model());
    }
}