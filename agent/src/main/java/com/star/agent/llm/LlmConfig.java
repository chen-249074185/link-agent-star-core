package com.star.agent.llm;

import com.star.agent.infra.config.AppConfig;
import com.star.agent.llm.client.DashScopeLlmClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 多模型注册表：一个 DashScopeLlmClient 类服务多个 qwen 模型（turbo 默认 @Component + max @Bean）
// 路由器按 ScenePolicy.model 名字匹配这里注册的客户端
@Configuration
public class LlmConfig {

    // 创作场景用 qwen-max（ScenePolicy 的 CREATION.model）
    // 注意用三参构造器：两参 (String, AppConfig) 会把 model 当成 baseUrl（坑）
    @Bean
    public DashScopeLlmClient qwenMaxClient(AppConfig appConfig) {
        return new DashScopeLlmClient("https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-max", appConfig);
    }
}