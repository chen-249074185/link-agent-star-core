package com.star.agent.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// MCP stdio 握手测试：子进程拉起 DemoEchoServer → initialize → listTools → callTool → 关闭
class McpClientHandshakeTest {

    @Test
    void handshake_echoAndAdd() {
        // 1. JSON 序列化器：和 server 侧同款，两边消息格式才一致
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());

        // 2. 子进程参数：复用当前 classpath 拉起 DemoEchoServer（测试跑时 classpath 已含 main 类与依赖）
        ServerParameters params = ServerParameters.builder("java")
                .args("-cp", System.getProperty("java.class.path"), "com.star.agent.mcp.DemoEchoServer")
                .build();

        // 3. 建客户端 + 连接（stdio 管道）
        StdioClientTransport transport = new StdioClientTransport(params, jsonMapper);
        transport.setStdErrorHandler(System.err::println);
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(10))
                .build();
        try {
            // 4. 握手第①步：initialize 互相报名字版本
            client.initialize();

            // 5. 握手第②步：listTools 拿工具菜单，应看到 echo/add 两个工具
            List<String> toolNames = client.listTools().tools().stream()
                    .map(McpSchema.Tool::name)
                    .toList();
            assertTrue(toolNames.contains("echo"), "应暴露 echo 工具");
            assertTrue(toolNames.contains("add"), "应暴露 add 工具");

            // 6. 握手第③步：callTool 调 echo 回显
            McpSchema.CallToolResult echoResult = client.callTool(
                    new McpSchema.CallToolRequest("echo", Map.of("text", "你好")));
            String echoText = ((McpSchema.TextContent) echoResult.content().get(0)).text();
            assertEquals("echo: 你好", echoText);

            // 7. callTool 调 add 两数相加
            McpSchema.CallToolResult addResult = client.callTool(
                    new McpSchema.CallToolRequest("add", Map.of("a", 2, "b", 3)));
            String addText = ((McpSchema.TextContent) addResult.content().get(0)).text();
            assertEquals("5", addText);

            // 8. 握手第④步：优雅关闭（通知 server 结束会话）
            assertTrue(client.closeGracefully(), "应优雅关闭");
        } finally {
            // 9. 兜底：closeGracefully 失败也要断开传输，不留资源
            client.close();
        }
    }
}