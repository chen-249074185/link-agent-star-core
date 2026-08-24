package com.star.liepin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

// Liepin MCP Server（Java 壳）：stdio 传输，把 MCP 请求翻译成官方 liepin-cli 子进程调用
// 工具：liepin_doctor 自检 / liepin_search_jobs 搜岗 / liepin_resume 读简历
// Token 从环境变量 LIEPIN_USER_TOKEN 读（主程序装配时注入）；CLI 路径 LIEPIN_CLI_PATH 可配
public class LiepinMcpServer {

    private static final ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) {
        String token = System.getenv("LIEPIN_USER_TOKEN");
        if (token == null || token.isBlank()) {
            System.err.println("缺少环境变量 LIEPIN_USER_TOKEN");
            System.exit(1);
        }
        String cliPath = LiepinCliRunner.resolveCliPath();
        if (!LiepinCliRunner.cliAvailable(cliPath)) {
            System.err.println("liepin-cli 不可用，请检查路径: " + cliPath);
            System.exit(1);
        }
        LiepinCliRunner runner = new LiepinCliRunner(cliPath, token);

        McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());
        EofAwareInputStream eofAware = new EofAwareInputStream(System.in);
        McpServerTransportProvider transport = new StdioServerTransportProvider(jsonMapper, eofAware, System.out);
        System.setOut(System.err);   // 日志走 stderr，不污染协议流（MCP stdio 铁律）

        try {
            McpServer.sync(transport)
                    .serverInfo("liepin-mcp-server", "0.1.0")
                    .tools(doctorTool(runner), searchJobsTool(runner), resumeTool(runner))
                    .build();
            System.err.println("liepin-mcp-server 就绪，等待客户端...");
        } catch (Exception e) {
            System.err.println("启动失败: " + e.getMessage());
            System.exit(1);
        }

        eofAware.awaitEof();
    }

    // 工具1：自检（Token 有效/CLI 可用/登录态）——出问题先跑它（R8 保险）
    private static SyncToolSpecification doctorTool(LiepinCliRunner runner) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("liepin_doctor")
                .description("猎聘接入自检：CLI 是否可用、Token 是否有效、能否读到简历")
                .inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null))
                .build();
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("CLI 路径: ").append(runner.cliPathVisible()).append("\n");
                    sb.append("CLI 可用: ").append(LiepinCliRunner.cliAvailable(runner.cliPathVisible())).append("\n");
                    sb.append("Token 已配置: ").append(runner.token() != null && !runner.token().isBlank()).append("\n");
                    try {
                        runner.run("--help");
                        sb.append("CLI 执行: 正常\n");
                    } catch (Exception e) {
                        sb.append("CLI 执行: 失败(").append(e.getMessage()).append(")\n");
                    }
                    try {
                        JsonNode r = parse(mapper, runner.run("resume", "get", "--output", "json"));
                        boolean ok = r.path("errCode").asInt() == 0;
                        sb.append("Token 有效性: ").append(ok ? "有效" : "无效").append("\n");
                        if (ok) {
                            sb.append("简历可读: 是");
                        }
                    } catch (Exception e) {
                        sb.append("Token 有效性: 校验失败(").append(e.getMessage()).append(")");
                    }
                    return ok(sb.toString());
                })
                .build();
    }

    // 工具2：搜岗位（关键词/城市/薪资下限/页码）→ 结构化列表
    private static SyncToolSpecification searchJobsTool(LiepinCliRunner runner) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("liepin_search_jobs")
                .description("按关键词/城市/薪资搜索猎聘职位，返回岗位列表")
                .inputSchema(new McpSchema.JsonSchema("object",
                        Map.of(
                                "keyword", Map.of("type", "string", "description", "职位关键词，如 Java开发"),
                                "address", Map.of("type", "string", "description", "城市，如 北京"),
                                "salaryFloor", Map.of("type", "integer", "description", "薪资下限(k/月)，如 10"),
                                "page", Map.of("type", "integer", "description", "页码，0 起")
                        ),
                        List.of("keyword"), null, null, null))
                .build();
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    Map<String, Object> a = request.arguments();
                    String keyword = String.valueOf(a.get("keyword"));
                    String address = a.get("address") == null ? "" : String.valueOf(a.get("address"));
                    int salary = a.get("salaryFloor") == null ? 0 : ((Number) a.get("salaryFloor")).intValue();
                    int page = a.get("page") == null ? 0 : ((Number) a.get("page")).intValue();

                    List<String> cmd = new java.util.ArrayList<>(List.of(
                            "job", "search", "--job-name", keyword, "--output", "json"));
                    if (!address.isBlank()) cmd.addAll(List.of("--address", address));
                    if (salary > 0) cmd.addAll(List.of("--salary-floor", String.valueOf(salary)));
                    cmd.addAll(List.of("--page", String.valueOf(page)));

                    try {
                        JsonNode r = parse(mapper, runner.run(cmd.toArray(new String[0])));
                        if (r.path("errCode").asInt() != 0) {
                            return error("猎聘返回错误: " + r.path("data").path("result").asText());
                        }
                        StringBuilder sb = new StringBuilder();
                        for (JsonNode j : r.path("data").path("list")) {
                            sb.append("jobId=").append(j.path("jobId").asLong())
                                    .append(" | ").append(j.path("jobName").asText())
                                    .append(" | ").append(j.path("company").asText())
                                    .append(" | ").append(j.path("salary").asText())
                                    .append(" | ").append(j.path("education").asText())
                                    .append(" | ").append(j.path("workYears").asText())
                                    .append(" | ").append(j.path("location").asText())
                                    .append("\n");
                        }
                        return ok(sb.isEmpty() ? "未找到匹配岗位" : sb.toString());
                    } catch (Exception e) {
                        return error("搜索失败: " + e.getMessage());
                    }
                })
                .build();
    }

    // 工具3：读简历（Markdown 全文，含基本信息/求职期望/教育/项目/自我评价）
    private static SyncToolSpecification resumeTool(LiepinCliRunner runner) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("liepin_resume")
                .description("读取用户当前简历全文（Markdown），用于分析求职画像/完善度")
                .inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null))
                .build();
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        JsonNode r = parse(mapper, runner.run("resume", "get", "--output", "json"));
                        if (r.path("errCode").asInt() != 0) {
                            return error("猎聘返回错误: " + r.path("data").path("result").asText());
                        }
                        String resume = r.path("data").path("result").asText();
                        return ok(resume.isBlank() ? "简历为空" : resume);
                    } catch (Exception e) {
                        return error("读取简历失败: " + e.getMessage());
                    }
                })
                .build();
    }

    private static JsonNode parse(ObjectMapper mapper, String json) throws Exception {
        return mapper.readTree(json);
    }

    private static McpSchema.CallToolResult ok(String text) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(text)))
                .build();
    }

    private static McpSchema.CallToolResult error(String msg) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(msg)))
                .build();
    }

    // EOF 感知流（坑21 同款）：客户端断开 → 主线程退出，零僵尸
    private static class EofAwareInputStream extends FilterInputStream {

        private final CountDownLatch eofLatch = new CountDownLatch(1);

        EofAwareInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b == -1) eofLatch.countDown();
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n == -1) eofLatch.countDown();
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
}