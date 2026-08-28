package com.star.agent.adapter.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

// Web 入口横幅：应用启动完成后在控制台打印各页面地址（直通前端，点开即用）
@Component
public class WebEntryBanner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(WebEntryBanner.class);

    private final String port;

    public WebEntryBanner(@Value("${server.port:8080}") String port) {
        this.port = port;
    }

    @Override
    public void run(ApplicationArguments args) {
        String base = "http://localhost:" + port;
        logger.info("\n================ 星链智核 · Web 入口 ================\n" +
                "  用户端: " + base + "/login.html\n" +
                "  管理端: " + base + "/manage.html  (X-Admin-Token 每次进入必输)\n" +
                "===================================================");
    }
}