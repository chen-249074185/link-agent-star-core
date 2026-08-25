package com.star.agent.adapter.wechat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertTrue;

// 正向验证：bot-enabled=true 时 WechatBotStarter 注册（仅验证 bean 存在，不真正启动 Bot 线程）
@SpringBootTest(properties = "linkagent.wechat.bot-enabled=true")
class WechatBotStarterEnabledTest {

    @Autowired
    ApplicationContext ctx;

    @Test
    void starterRegistered() {
        assertTrue(ctx.containsBean("wechatBotStarter"), "bot-enabled=true 应注册启动器");
    }
}