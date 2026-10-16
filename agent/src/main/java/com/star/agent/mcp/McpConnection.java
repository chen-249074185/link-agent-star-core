package com.star.agent.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

// MCP 连接管理（真实轨）：连接一个 MCP server → 握手 → 工具调用
// 两种传输模式：
//   stdio 模式：拉起 MCP server 子进程（java -jar，rag/crawl/liepin 本地 fat jar）
//   http 模式：远程 Streamable HTTP（官方 MCP，Bearer Token 认证，无子进程）
// 由 McpServerConfig 按 server 配置创建多个实例（各有 serverName）
// R9：连接失败/失联 → 日志告警 + 返回空，主程序不崩（能力降级而非宕机）
public class McpConnection {

    private static final Logger logger = LoggerFactory.getLogger(McpConnection.class);

    private final String serverName;                // 如 rag-mcp / crawl-mcp / luckin-mcp（Skill 来源标注用）
    private final String jarPath;                   // stdio 模式：fat jar 路径（null = http 模式）
    private final Map<String, String> extraEnv;     // stdio 模式：子进程额外环境变量（如猎聘 Token，走环境不落命令行）
    private final String httpUrl;                   // http 模式：远程 MCP 端点（null = stdio 模式）
    private final String bearerToken;               // http 模式：Bearer Token（Authorization 头，零明文）
    private final String warmUpTool;                // 预热探针工具名（stdio 模式用；HTTP 模式跳过预热，见 warmUp()）
    private final McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());

    private McpSyncClient client;                   // null = 未连接（含连接失败）
    private volatile int generation = 0;            // 连接代数：重建后 +1，供并发调用方判断"连接已被别人重建"

    // stdio 模式：本地 MCP server 子进程
    public McpConnection(String serverName, String jarPath) {
        this(serverName, jarPath, Map.of());
    }

    public McpConnection(String serverName, String jarPath, Map<String, String> extraEnv) {
        this.serverName = serverName;
        this.jarPath = jarPath;
        this.extraEnv = extraEnv;
        this.httpUrl = null;
        this.bearerToken = null;
        this.warmUpTool = null;
    }

    // http 模式：远程 Streamable HTTP MCP server（官方 MCP）
    public McpConnection(String serverName, String httpUrl, String bearerToken, String warmUpTool) {
        this.serverName = serverName;
        this.jarPath = null;
        this.extraEnv = Map.of();
        this.httpUrl = httpUrl;
        this.bearerToken = bearerToken;
        this.warmUpTool = warmUpTool;
    }

    public String serverName() {
        return serverName;
    }

    // 连接 + 握手（connect→initialize→listTools）；失败返回空表并清理，主程序继续跑
    public List<McpSchema.Tool> connect() {
        try {
            McpClientTransport transport = buildTransport();
            client = McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(60))
                    .build();
            client.initialize();
            List<McpSchema.Tool> tools = client.listTools().tools();
            logger.info("{} 连接成功，工具 {} 个: {}", serverName, tools.size(),
                    tools.stream().map(McpSchema.Tool::name).toList());
            warmUp();   // 启动预热：第一次真实调用提前到启动期，失败走重连，避免用户使用时才踩雷
            return tools;
        } catch (Exception e) {
            logger.warn("{} 连接失败，MCP 技能不注册: {}", serverName, e.getMessage());
            close();
            return List.of();
        }
    }

    // 按模式构建传输：stdio = 子进程；http = 远程端点 + Bearer Token
    private McpClientTransport buildTransport() {
        if (httpUrl != null) {
            // 远程 Streamable HTTP：customizeRequest 注入 Authorization + Accept 头
            // 注意：builder(baseUrl) 只认 baseUrl（协议+主机+端口），SDK 默认 endpoint=/mcp——
            // 瑞幸实际端点是 /order/user/mcp，必须拆开传（缺路径会打错地址 → 401 Authorization error，实测排坑）
            // Accept 必须带 application/json（纯 text/event-stream 会 405/401，实测只有
            // "application/json, text/event-stream" 才握手成功）
            java.net.URI uri = java.net.URI.create(httpUrl);
            String base = uri.getScheme() + "://" + uri.getAuthority();
            String path = uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath();
            HttpClientStreamableHttpTransport.Builder b = HttpClientStreamableHttpTransport.builder(base)
                    .endpoint(path)
                    .customizeRequest(req -> {
                        req.header("Authorization", "Bearer " + bearerToken);
                        req.header("Accept", "application/json, text/event-stream");
                    })
                    .connectTimeout(Duration.ofSeconds(30));
            return b.build();
        }
        // 子进程：java -jar（环境变量继承主进程；extraEnv 补充注入，如猎聘 Token）
        ServerParameters.Builder b = ServerParameters.builder("java")
                .args("-jar", jarPath);
        if (!extraEnv.isEmpty()) {
            b.env(extraEnv);
        }
        ServerParameters params = b.build();
        StdioClientTransport transport = new StdioClientTransport(params, jsonMapper);
        transport.setStdErrorHandler(line -> logger.warn("[{}] {}", serverName, line));  // 子进程日志转主程序
        return transport;
    }

    // 启动预热：stdio 子进程 session 在首次调用时偶发自关闭（Windows 单边断开，见记忆文档 R13），
    // 预热让问题提前暴露并触发重连。HTTP 模式跳过——远程服务每次调用都是独立请求，握手成功即证明连接可用，
    // 预热反而会因业务工具参数校验失败（如瑞幸空参数 405）误触发重连死循环
    private void warmUp() {
        if (client == null || httpUrl != null) {
            return;
        }
        String probe = "rag-mcp".equals(serverName) ? "rag_admin_list_all"
                : "liepin-mcp".equals(serverName) ? "liepin_doctor" : warmUpTool != null ? warmUpTool : "crawl_fetch";
        if (probe == null || probe.isBlank()) {
            return;
        }
        try {
            client.callTool(new McpSchema.CallToolRequest(probe, Map.of()));
            logger.info("{} 预热调用成功", serverName);
        } catch (Exception e) {
            logger.warn("{} 预热调用失败（{}），重建连接: {}", serverName, e.getMessage(), e.getClass().getSimpleName());
            reconnect();
        }
    }

    // 调 MCP 工具（参数由调用方拼好），返回文本结果
    // 防永久卡死（R13）：调用异常/超时 → 自动重建子进程连接并重试一次；
    // 并发失败时仅首个线程重建（generation 判断），其余线程复用新连接重试，不重复杀连接；
    // 重建失败/重试仍失败 → 抛异常，由上层 R9 降级话术兜底，主流程不崩
    public String callTool(String toolName, Map<String, Object> args) {
        if (client == null) {
            throw new IllegalStateException(serverName + " 未连接");
        }
        int g = generation;
        try {
            return doCall(toolName, args);
        } catch (Exception e) {
            synchronized (this) {
                if (g == generation) {
                    logger.warn("{} 调用 {} 失败（{}），重建连接重试: {}", serverName, toolName,
                            e.getClass().getSimpleName(), e.getMessage());
                    reconnect();
                } else {
                    logger.warn("{} 调用 {} 失败（{}），连接已被重建，直接重试", serverName, toolName,
                            e.getClass().getSimpleName());
                }
            }
            if (client == null) {
                throw new IllegalStateException(serverName + " 重连失败，能力暂不可用");
            }
            return doCall(toolName, args);
        }
    }

    private String doCall(String toolName, Map<String, Object> args) {
        // SDK 1.1.4 的 outbound sink 是 Sinks.many().unicast()——并发 tryEmitNext 会返回
        // FAIL_NON_SERIALIZED 并抛 "Failed to enqueue message"（非线程安全 emit 缺陷）。
        // 冲突窗口是微秒级：短暂等待后重试即可成功，不必重建连接（重建反而杀子进程）
        for (int attempt = 0; ; attempt++) {
            try {
                var result = client.callTool(new McpSchema.CallToolRequest(toolName, args));
                return ((McpSchema.TextContent) result.content().get(0)).text();
            } catch (Exception e) {
                if (attempt < 2 && e.getMessage() != null
                        && e.getMessage().contains("Failed to enqueue")) {
                    try {
                        Thread.sleep(80);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                    continue;
                }
                throw e;
            }
        }
    }

    // 重建连接：关闭旧 client（可能已死，尽力而为）→ 重新拉起子进程 + 握手
    // synchronized：并发调用同时失败时只重连一次，其余线程串行等待后复用新连接
    private synchronized void reconnect() {
        close();
        if (connect().isEmpty()) {
            logger.warn("{} 重建连接失败", serverName);
        } else {
            generation++;
            logger.info("{} 重建连接成功（代数 {}）", serverName, generation);
        }
    }

    // 应用退出时优雅关闭子进程（closeGracefully → 子进程 EOF 感知退出，零僵尸， 解法）
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