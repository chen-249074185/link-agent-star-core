package com.star.agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// 测试上下文不起微信 Bot（避免测试里扫码阻塞）
@SpringBootTest(properties = "linkagent.wechat.bot-enabled=false")
class AgentApplicationTests {

	@Test
	void contextLoads() {
	}

}
