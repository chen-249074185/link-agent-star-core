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
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

// MCP 连接管理（真实轨）：拉起一个 MCP server 子进程（stdio）→ 握手 → 工具调用
// 由 McpServerConfig 按 server 配置创建多个实例（rag-mcp / crawl-mcp，各有 serverName）
// R9：连接失败/子进程失联 → 日志告警 + 返回空，主程序不崩（能力降级而非宕机）
public class McpConnection {

    private static final Logger logger = LoggerFactory.getLogger(McpConnection.class);

    private final String serverName;                // 如 rag-mcp / crawl-mcp（Skill 来源标注用）
    private final String jarPath;                   // fat jar 路径（application.properties）
    private final Map<String, String> extraEnv;     // 子进程额外环境变量（如猎聘 Token，走环境不落命令行）
    private final McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());

    private McpSyncClient client;                   // null = 未连接（含连接失败）

    public McpConnection(String serverName, String jarPath) {
        this(serverName, jarPath, Map.of());
    }

    public McpConnection(String serverName, String jarPath, Map<String, String> extraEnv) {
        this.serverName = serverName;
        this.jarPath = jarPath;
        this.extraEnv = extraEnv;
    }

    public String serverName() {
        return serverName;
    }

    // 启动子进程 + 握手（connect→initialize→listTools）；失败返回空表并清理，主程序继续跑
    public List<McpSchema.Tool> connect() {
        try {
            // 子进程：java -jar（环境变量继承主进程；extraEnv 补充注入，如猎聘 Token）
            ServerParameters.Builder b = ServerParameters.builder("java")
                    .args("-jar", jarPath);
            if (!extraEnv.isEmpty()) {
                b.env(extraEnv);
            }
            ServerParameters params = b.build();
            StdioClientTransport transport = new StdioClientTransport(params, jsonMapper);
            transport.setStdErrorHandler(line -> logger.warn("[{}] {}", serverName, line));  // 子进程日志转主程序
            client = McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(60))
                    .build();
            client.initialize();
            List<McpSchema.Tool> tools = client.listTools().tools();
            logger.info("{} 连接成功，工具 {} 个: {}", serverName, tools.size(),
                    tools.stream().map(McpSchema.Tool::name).toList());
            return tools;
        } catch (Exception e) {
            logger.warn("{} 连接失败，MCP 技能不注册: {}", serverName, e.getMessage());
            close();
            return List.of();
        }
    }

    // 调 MCP 工具（参数由调用方拼好），返回文本结果
    public String callTool(String toolName, Map<String, Object> args) {
        if (client == null) {
            throw new IllegalStateException(serverName + " 未连接");
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
                logger.warn("关闭 {} 连接失败: {}", serverName, e.getMessage());
            }
            client = null;
        }
    }
}