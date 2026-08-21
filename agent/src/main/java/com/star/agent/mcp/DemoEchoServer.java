package com.star.agent.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import tools.jackson.databind.json.JsonMapper;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

// MCP 示例服务器（双轨验证）：暴露 echo/add 两个工具，走 stdio 传输，供客户端握手测试
public class DemoEchoServer {

    public static void main(String[] args) {
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());

        // 1. EOF 感知流：SDK 的 transport 从它读 stdin，客户端关闭管道（EOF）时唤醒主线程退出
        //    （坑：直接 Thread.currentThread().join() 会永久阻塞，客户端断开后子进程变僵尸）
        EofAwareInputStream eofAware = new EofAwareInputStream(System.in);

        // 2. stdio 传输：先 new transport（构造时固化 System.out = 协议输出），再 setOut 让日志走 stderr
        //    顺序反了会导致协议消息也进 stderr，客户端在 stdout 读不到响应
        McpServerTransportProvider transport = new StdioServerTransportProvider(jsonMapper, eofAware, System.out);
        System.setOut(System.err);

        // 3. 组装服务器：起个名字 + 挂上两个工具
        McpServer.sync(transport)
                .serverInfo("demo-echo-server", "1.0.0")
                .tools(echoTool(), addTool())
                .build();

        // 4. 阻塞等客户端断开：EOF 一到 main 就退出，JVM 干净收尾（无僵尸进程）
        eofAware.awaitEof();
    }

    // EOF 感知流：包装 System.in，读到 EOF（-1）时放行等待者
    private static class EofAwareInputStream extends FilterInputStream {

        private final CountDownLatch eofLatch = new CountDownLatch(1);

        EofAwareInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b == -1) {
                eofLatch.countDown();
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n == -1) {
                eofLatch.countDown();
            }
            return n;
        }

        void awaitEof() {
            try {
                eofLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // 工具1：echo 回显
    private static SyncToolSpecification echoTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("echo")
                .description("回显输入的文本")
                .inputSchema(new McpSchema.JsonSchema("object",
                        Map.of("text", Map.of("type", "string")),
                        List.of("text"), null, null, null))
                .build();
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(
                                "echo: " + request.arguments().get("text"))))
                        .build())
                .build();
    }

    // 工具2：add 两数相加
    private static SyncToolSpecification addTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("add")
                .description("两个整数相加")
                .inputSchema(new McpSchema.JsonSchema("object",
                        Map.of("a", Map.of("type", "integer"),
                                "b", Map.of("type", "integer")),
                        List.of("a", "b"), null, null, null))
                .build();
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    int a = ((Number) args.get("a")).intValue();
                    int b = ((Number) args.get("b")).intValue();
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(new McpSchema.TextContent(String.valueOf(a + b))))
                            .build();
                })
                .build();
    }
}