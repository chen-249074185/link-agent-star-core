package com.star.agent.llm.client;

import com.star.agent.infra.config.AppConfig;
import com.star.agent.llm.ChatMessage;
import com.star.agent.llm.ChatResponse;
import com.star.agent.llm.ToolCall;
import com.star.agent.llm.ToolSpec;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // 流式工具订单拼接：id/name 只出现在首块，arguments 跨多块增量到达，必须拼回完整 JSON
    // SSE 行为（OpenAI/DeepSeek 兼容）：每条 delta.tool_calls 带 index 定位是哪条订单
    @Test
    void streamWithTools_accumulatesSplitToolCalls() throws Exception {
        String sse = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"\"}}]}}]}\n"
                + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"北京\\\"}\"}}]}}]}\n"
                + "data: [DONE]\n";

        HttpServer server = startServer(sse);
        try {
            DeepSeekLlmClient client = new DeepSeekLlmClient("http://127.0.0.1:" + server.getAddress().getPort(), new AppConfig());
            List<String> tokens = new ArrayList<>();

            ChatResponse resp = client.streamWithTools(
                    List.of(new ChatMessage("user", "北京天气")), List.of(), tokens::add);

            assertEquals(1, resp.toolCalls().size());            // 两块碎片拼成一条订单
            ToolCall call = resp.toolCalls().get(0);
            assertEquals("call_1", call.id());
            assertEquals("get_weather", call.functionName());
            assertEquals("{\"city\":\"北京\"}", call.arguments());
            assertTrue(tokens.isEmpty());                        // 工具订单流没有正文 token
        } finally {
            server.stop(0);
        }
    }

    // 两条工具订单并行出现（index 0/1），各自独立拼接不串数据
    @Test
    void streamWithTools_multipleToolCallsByIndex() throws Exception {
        String sse = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"a\",\"arguments\":\"{}\"}}]}}]}\n"
                + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,\"id\":\"c2\",\"function\":{\"name\":\"b\",\"arguments\":\"{\\\"x\\\":\"}}]}}]}\n"
                + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,\"function\":{\"arguments\":\"1}\"}}]}}]}\n"
                + "data: [DONE]\n";

        HttpServer server = startServer(sse);
        try {
            DeepSeekLlmClient client = new DeepSeekLlmClient("http://127.0.0.1:" + server.getAddress().getPort(), new AppConfig());

            ChatResponse resp = client.streamWithTools(
                    List.of(new ChatMessage("user", "hi")), List.of(), t -> {});

            assertEquals(2, resp.toolCalls().size());
            assertEquals("c1", resp.toolCalls().get(0).id());
            assertEquals("a", resp.toolCalls().get(0).functionName());
            assertEquals("{}", resp.toolCalls().get(0).arguments());
            assertEquals("c2", resp.toolCalls().get(1).id());
            assertEquals("b", resp.toolCalls().get(1).functionName());
            assertEquals("{\"x\":1}", resp.toolCalls().get(1).arguments());   // index 1 的碎片拼到自己的订单
        } finally {
            server.stop(0);
        }
    }

    // 合流版与纯流式共用解析路径：带 tools 请求、正文逐 token 回调、返回无工具订单
    @Test
    void streamWithTools_plainContentPassesThrough() throws Exception {
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"北京\"}}]}\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"晴\"}}]}\n"
                + "data: [DONE]\n";

        HttpServer server = startServer(sse);
        try {
            DeepSeekLlmClient client = new DeepSeekLlmClient("http://127.0.0.1:" + server.getAddress().getPort(), new AppConfig());
            List<String> tokens = new ArrayList<>();

            ChatResponse resp = client.streamWithTools(
                    List.of(new ChatMessage("user", "天气")), List.of(), tokens::add);

            assertEquals("北京晴", resp.content());               // 返回值 = 完整正文
            assertEquals("北京晴", String.join("", tokens));      // 回调 = 逐 token
            assertTrue(resp.toolCalls().isEmpty());
        } finally {
            server.stop(0);
        }
    }

    private HttpServer startServer(String sse) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] resp = sse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        return server;
    }
}