package com.star.agent.infra.config;

import com.star.agent.infra.web.AdminAuthFilter;
import com.star.agent.infra.web.JwtAuthInterceptor;
import com.star.agent.infra.web.AdminAuthFilter;
import com.star.agent.infra.web.JwtAuthInterceptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// Web 配置（鉴权双轨注册中心，W12 10-27）：
//   用户轨：JwtAuthInterceptor（@Component）拦 /api/**，排除登录注册白名单 + 管理端路径
//   管理轨：AdminAuthFilter 拦 /api/admin/*（X-Admin-Token 比对 ADMIN_TOKEN_WEB）
@Configuration
public class WebConfig implements WebMvcConfigurer {

    // 登录注册与内部采集工具免 JWT（采集工具走 curl/脚本，管理端/小程序后续迁移）
    private static final String[] JWT_EXCLUDE = {
            "/api/user/send-code", "/api/user/register", "/api/user/login",
            "/api/user/scan-login",   // 微信扫码免密登录（未登录才能调）
            "/api/collect", "/api/reindex", "/api/admin/**",
            "/api/wechat/scan/**"   // 微信扫码（登录页未登录也要能扫码）
    };

    private final JwtAuthInterceptor jwtAuthInterceptor;

    public WebConfig(JwtAuthInterceptor jwtAuthInterceptor) {
        this.jwtAuthInterceptor = jwtAuthInterceptor;
    }

    @Bean
    public FilterRegistrationBean<AdminAuthFilter> adminAuthFilter(ObjectProvider<AppConfig> configProvider) {
        FilterRegistrationBean<AdminAuthFilter> reg = new FilterRegistrationBean<>(new AdminAuthFilter(configProvider));
        reg.addUrlPatterns("/api/admin/*");
        return reg;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtAuthInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(JWT_EXCLUDE);
    }
}