package com.star.agent.infra.config;

import org.springframework.stereotype.Component;

@Component
public class AppConfig {
    private final String dashScopeApiKey = System.getenv("DASHSCOPE_API_KEY");

    private final String adminToken = System.getenv("ADMIN_TOKEN");

    public String dashScopeApiKey(){
        return dashScopeApiKey;
    }

    public String adminToken(){
        return adminToken;
    }
}
