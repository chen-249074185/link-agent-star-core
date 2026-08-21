package com.star.agent.skill.impl.mock;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertTrue;

// 正向验证：mock-enabled=true 时 4 个 Mock 占位 Skill 全部注册进容器
@SpringBootTest(properties = "linkagent.workflow.mock-enabled=true")
class MockSkillsEnabledTest {

    @Autowired
    private ApplicationContext ctx;

    @Test
    void mockSkillsRegisteredWhenEnabled() {
        assertTrue(ctx.containsBean("ragSearchSkill"));
        assertTrue(ctx.containsBean("crawlSkill"));
        assertTrue(ctx.containsBean("liepinSkill"));
        assertTrue(ctx.containsBean("uuDeliverySkill"));
    }
}
