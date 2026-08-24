package com.star.agent.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

// MCP 连接管理（真实轨）：拉起 rag-mcp fat jar 子进程（stdio）→ 握手 → 工具调用
// 仅 mock-enabled=false 时启用（true 时 Mock 占位技能在位，本类不注册 bean）
// R9：连接失败/子进程失联 → 日志告警 + 返回空，主程序不崩（能力降级而非宕机）
@Component
@ConditionalOnProperty(name = "linkagent.workflow.mock-enabled", havingValue = "false")
public class McpConnection {

    private static final Logger logger = LoggerFactory.getLogger(McpConnection.class);

    private final String ragJar;                    // rag-mcp fat jar 路径（application.properties）
    private final McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());

    private McpSyncClient client;                   // null = 未连接（含连接失败）

    public McpConnection(@Value("${linkagent.mcp.rag-jar:../rag-mcp/target/rag-mcp-0.0.1-SNAPSHOT.jar}") String ragJar) {
        this.ragJar = ragJar;
    }

    // 启动子进程 + 握手（connect→initialize→listTools）；失败返回空表并清理，主程序继续跑
    public List<McpSchema.Tool> connect() {
        try {
            // 子进程：java -jar rag-mcp（环境变量继承主进程，DASHSCOPE_API_KEY 自然带过去）
            ServerParameters params = ServerParameters.builder("java")
                    .args("-jar", ragJar)
                    .build();
            StdioClientTransport transport = new StdioClientTransport(params, jsonMapper);
            transport.setStdErrorHandler(line -> logger.warn("[rag-mcp] {}", line));  // 子进程日志转主程序日志
            client = McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(60))
                    .build();
            client.initialize();
            List<McpSchema.Tool> tools = client.listTools().tools();
            logger.info("rag-mcp 连接成功，工具 {} 个: {}", tools.size(),
                    tools.stream().map(McpSchema.Tool::name).toList());
            return tools;
        } catch (Exception e) {
            logger.warn("rag-mcp 连接失败，MCP 技能不注册: {}", e.getMessage());
            close();
            return List.of();
        }
    }

    // 调 MCP 工具（参数由调用方拼好，含 user_id/kb_id），返回文本结果
    public String callTool(String toolName, Map<String, Object> args) {
        if (client == null) {
            throw new IllegalStateException("rag-mcp 未连接");
        }
        var result = client.callTool(new McpSchema.CallToolRequest(toolName, args));
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    // 应用退出时优雅关闭子进程（closeGracefully → 子进程 EOF 感知退出，零僵尸，坑21 解法）
    @PreDestroy
    public void close() {
        if (client != null) {
            try {
                client.closeGracefully();
            } catch (Exception e) {
                logger.warn("关闭 rag-mcp 连接失败: {}", e.getMessage());
            }
            client = null;
        }
    }
}