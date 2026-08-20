package com.star.agent.infra.config;

import org.springframework.stereotype.Component;

@Component
public class AppConfig {
    private final String dashScopeApiKey = System.getenv("DASHSCOPE_API_KEY");

    private final String adminToken = System.getenv("ADMIN_TOKEN");

    private final String deepseekApiKey = System.getenv("DEEPSEEK_API_KEY");

    private final String seniverseApiKey = System.getenv("SENIVERSE_API_KEY");

    private final String amapApiKey = System.getenv("AMAP_API_KEY");

    public String dashScopeApiKey(){
        return dashScopeApiKey;
    }

    public String adminToken(){
        return adminToken;
    }

    public String deepseekApiKey(){
        return deepseekApiKey;
    }

    public String seniverseApiKey(){return seniverseApiKey; }

    public String amapApiKey(){ return amapApiKey; }

}
