package com.star.agent.skill.impl;

import com.star.agent.skill.SkillContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CommuteSkillTest {

    @Test
    void commuteReturnsMinutes() throws Exception {
        // 假高德服务器吐的响应：route.paths[0].duration = 1800 秒 = 30 分钟
        String json = "{\"route\":{\"paths\":[{\"duration\":\"1800\",\"distance\":\"15000\"}]}}";

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v3/direction/driving", exchange -> {
            byte[] resp = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v3/direction/driving";
            CommuteSkill skill = new CommuteSkill(baseUrl, "test");
            SkillContext ctx = new SkillContext("user1",
                    "{\"origin\":\"116.481028,39.989643\",\"destination\":\"116.465302,40.004717\"}", "");
            var result = skill.execute(ctx);
            assertTrue(result.success());
            assertTrue(result.content().contains("30分钟"));
        } finally {
            server.stop(0);
        }
    }
}