package com.star.agent.skill.impl.mock;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertFalse;

// 反向验证：mock-enabled=false 时 4 个 Mock 全部不注册（为 W9 真实 MCP 接管腾位子）
@SpringBootTest(properties = "linkagent.workflow.mock-enabled=false")
class MockSkillsDisabledTest {

    @Autowired
    private ApplicationContext ctx;

    @Test
    void mockSkillsNotRegisteredWhenDisabled() {
        assertFalse(ctx.containsBean("ragSearchSkill"));
        assertFalse(ctx.containsBean("crawlSkill"));
        assertFalse(ctx.containsBean("liepinSkill"));
        assertFalse(ctx.containsBean("uuDeliverySkill"));
    }
}
