package com.star.agent.llm.client;

import com.star.agent.infra.config.AppConfig;
import com.star.agent.llm.ChatMessage;
import com.star.agent.llm.ToolSpec;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeepSeekStreamTest {

    @Test
    void streamCollectsTokensInOrder() throws Exception {
        // ① 假服务器要吐的响应：2 段内容 + 1 个结束标记
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n"
                + "data: [DONE]\n";

        // ② 起服务器（127.0.0.1 + 端口 0 = 随机空闲端口）
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // ③ 定义 /chat/completions 请求的处理逻辑：原样吐 sse
        server.createContext("/chat/completions", exchange -> {
            byte[] resp = sse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        try {
            // ④ 客户端指向本地假服务器（构造器 2 的用途就在这）
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            DeepSeekLlmClient client = new DeepSeekLlmClient(baseUrl, new AppConfig());

            // ⑤ 调用方：每收到一个片段就存进 List
            List<String> tokens = new ArrayList<>();
            client.stream(List.of(new ChatMessage("user", "你好")), List.of(), tokens::add);

            // ⑥ 断言：两个片段拼起来 = "你好"
            assertEquals("你好", String.join("", tokens));
        } finally {
            server.stop(0);   // 不管成不成，finally 里关服务器
        }
    }
}