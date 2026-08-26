package com.star.agent.skill.impl;

import com.sun.net.httpserver.HttpServer;
import com.star.agent.skill.SkillContext;
import com.star.agent.skill.SkillResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

// WeatherSkill 单测：本地假心知天气服务器，验证 当前天气 / 未来预报（明天）解析
class WeatherSkillTest {

    private HttpServer server;
    private WeatherSkill skill;
    private String requestedPath = "";

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestedPath = exchange.getRequestURI().getPath() + "?" + exchange.getRequestURI().getRawQuery();
            String body;
            if (exchange.getRequestURI().getPath().endsWith("now.json")) {
                body = "{\"results\":[{\"now\":{\"text\":\"晴\",\"temperature\":\"29\"}}]}";
            } else {
                body = "{\"results\":[{\"daily\":["
                        + "{\"date\":\"2026-08-27\",\"text_day\":\"晴\",\"text_night\":\"多云\",\"high\":\"31\",\"low\":\"24\"},"
                        + "{\"date\":\"2026-08-28\",\"text_day\":\"小雨\",\"text_night\":\"阴\",\"high\":\"28\",\"low\":\"22\"}"
                        + "]}]}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        skill = new WeatherSkill("http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void nowWeather_parses() {
        SkillResult r = skill.execute(new SkillContext("u1", "{\"city\":\"湖州\"}", ""));

        assertTrue(r.success());
        assertTrue(r.content().contains("当前天气"), r.content());
        assertTrue(r.content().contains("29℃"));
        assertTrue(requestedPath.contains("now.json"), "days=1 走实时接口");
    }

    // 未来预报：days=2 → daily 接口，输出今天+明天（模型问"明天天气"传 days=2 即可直接作答）
    @Test
    void forecastDays2_parsesTodayAndTomorrow() {
        SkillResult r = skill.execute(new SkillContext("u1", "{\"city\":\"湖州\",\"days\":2}", ""));

        assertTrue(r.success());
        assertTrue(r.content().contains("今天"), r.content());
        assertTrue(r.content().contains("明天"), "明天预报必须解析出来");
        assertTrue(r.content().contains("小雨"), "明天是小雨");
        assertTrue(requestedPath.contains("daily.json"), "days=2 走预报接口");
        assertTrue(requestedPath.contains("days=2"));
    }

    @Test
    void missingCity_returnsHelp() {
        SkillResult r = skill.execute(new SkillContext("u1", "{}", ""));
        assertTrue(r.content().contains("城市"));
    }
}