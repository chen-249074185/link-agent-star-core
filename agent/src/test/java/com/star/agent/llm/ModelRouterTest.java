package com.star.agent.llm;

import com.star.agent.scene.Scene;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

// 多模型路由单测：@SpringBootTest 起全上下文（ScenePolicy 表 + 三个客户端都真实装配）
@SpringBootTest
class ModelRouterTest {

    @Autowired
    private ModelRouter router;

    // 创作场景 → ScenePolicy 配 qwen-max → qwenMaxClient
    @Test
    void creationRoutesToQwenMax() {
        assertEquals("qwen-max", router.clientFor(Scene.CREATION).model());
    }

    // 闲聊/办公/数据/群聊 → deepseek-v4-flash
    @Test
    void otherScenesRouteToDeepSeek() {
        assertEquals("deepseek-v4-flash", router.clientFor(Scene.CHAT).model());
        assertEquals("deepseek-v4-flash", router.clientFor(Scene.OFFICE).model());
        assertEquals("deepseek-v4-flash", router.clientFor(Scene.DATA).model());
        assertEquals("deepseek-v4-flash", router.clientFor(Scene.GROUP).model());
    }
}