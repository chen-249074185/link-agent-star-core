package com.star.agent.adapter.wechat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertFalse;

// 反向验证：bot-enabled=false 时 WechatBotStarter 不注册（测试上下文默认不起微信 Bot）
@SpringBootTest(properties = "linkagent.wechat.bot-enabled=false")
class WechatBotStarterDisabledTest {

    @Autowired
    ApplicationContext ctx;

    @Test
    void starterNotRegistered() {
        assertFalse(ctx.containsBean("wechatBotStarter"), "bot-enabled=false 不应注册启动器");
    }
}