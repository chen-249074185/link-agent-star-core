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
    private static final int MAX_APPLY_PER_DAY = 20;         // 计划要求：投递单日限次
    private static final long MIN_APPLY_INTERVAL_MS = 3000;  // 真实投递间隔下限
    private static final long MAX_APPLY_INTERVAL_MS = 8000;  // 真实投递间隔上限

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
                    .tools(doctorTool(runner), searchJobsTool(runner),
                            filterJobsTool(runner), resumeTool(runner),
                            applyDryRunTool(runner), applyTool(runner))
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
                    try {
                        JsonNode r = doSearch(runner,
                                String.valueOf(a.get("keyword")),
                                a.get("address") == null ? "" : String.valueOf(a.get("address")),
                                a.get("salaryFloor") == null ? 0 : ((Number) a.get("salaryFloor")).intValue(),
                                a.get("page") == null ? 0 : ((Number) a.get("page")).intValue());
                        StringBuilder sb = new StringBuilder();
                        for (JsonNode j : r.path("list")) {
                            sb.append(formatJob(j)).append("\n");
                        }
                        return ok(sb.isEmpty() ? "未找到匹配岗位" : sb.toString());
                    } catch (Exception e) {
                        return error("搜索失败: " + e.getMessage());
                    }
                })
                .build();
    }

    // 工具3：按求职画像精筛（学历/经验/薪资/城市四维，规则在 liepin-filter-rules.yaml）
    // 支持 overrides 对话式覆盖：用户说"看硕士岗"→ LLM 传 {"education":"硕士"}，其他维度 YAML 兜底
    private static SyncToolSpecification filterJobsTool(LiepinCliRunner runner) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("liepin_filter_jobs")
                .description("搜索并按求职画像（学历/经验/薪资/城市）精筛，返回能投的岗位及匹配理由；"
                        + "可用 overrides 临时覆盖规则（如 {\"education\":\"硕士\"}），未覆盖维度用默认画像")
                .inputSchema(new McpSchema.JsonSchema("object",
                        Map.of(
                                "keyword", Map.of("type", "string", "description", "职位关键词"),
                                "address", Map.of("type", "string", "description", "城市"),
                                "page", Map.of("type", "integer", "description", "页码，0 起"),
                                "overrides", Map.of("type", "string",
                                        "description", "规则覆盖 JSON，如 {\"education\":\"硕士\",\"salaryFloorK\":8,\"cities\":[\"北京\",\"成都\"]}")
                        ),
                        List.of("keyword"), null, null, null))
                .build();
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    Map<String, Object> a = request.arguments();
                    try {
                        FilterRules rules = FilterRules.load();
                        Object ov = a.get("overrides");
                        if (ov != null && !String.valueOf(ov).isBlank()) {
                            try {
                                rules = rules.withOverride(mapper.convertValue(
                                        mapper.readTree(String.valueOf(ov)), Map.class));
                            } catch (Exception ignored) {
                                // 覆盖参数解析失败：忽略，用默认画像（坏参数不挂工具）
                            }
                        }
                        JsonNode r = doSearch(runner,
                                String.valueOf(a.get("keyword")),
                                a.get("address") == null ? "" : String.valueOf(a.get("address")),
                                0,
                                a.get("page") == null ? 0 : ((Number) a.get("page")).intValue());
                        List<JobFilter.Match> matches = JobFilter.filter(r.path("list"), rules);
                        if (matches.isEmpty()) {
                            return ok("没有符合求职画像的岗位（画像: 学历" + rules.education()
                                    + " 经验" + rules.workYearKeywords()
                                    + " 薪资≥" + rules.salaryFloorK() + "k 城市" + rules.cities() + "）");
                        }
                        StringBuilder sb = new StringBuilder();
                        for (JobFilter.Match m : matches) {
                            sb.append(formatJob(m.job())).append(" | ").append(m.reason()).append("\n");
                        }
                        return ok(sb.toString());
                    } catch (Exception e) {
                        return error("精筛失败: " + e.getMessage());
                    }
                })
                .build();
    }

    // 工具5：投递计划 dry-run（只生成拟投清单，不真实投递；确认后才走真实投递）
    private static SyncToolSpecification applyDryRunTool(LiepinCliRunner runner) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("liepin_apply_dry_run")
                .description("生成投递计划清单（不真实投递）：传入岗位 JSON 数组（含 jobId/jobType，从搜索结果取）")
                .inputSchema(new McpSchema.JsonSchema("object",
                        Map.of("jobs", Map.of("type", "string",
                                "description", "岗位 JSON 数组，如 [{\"jobId\":1,\"jobType\":\"2\",\"jobName\":\"..\",\"company\":\"..\"}]")),
                        List.of("jobs"), null, null, null))
                .build();
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        return ok(ApplyPlanner.buildDryRunPlan(
                                String.valueOf(request.arguments().get("jobs"))));
                    } catch (Exception e) {
                        return error("生成投递计划失败: " + e.getMessage());
                    }
                })
                .build();
    }

    // 工具6：投递（双模式）——默认模拟（LIEPIN_SIMULATE=true，流程全走但不真投官方接口）；false 走真实投递
    // 双模式共用：参数校验 + 单日限次 20；真实模式额外：随机间隔 3~8s + 调官方 job apply
    private static SyncToolSpecification applyTool(LiepinCliRunner runner) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("liepin_apply")
                .description("投递一个职位（HIGH_RISK 需确认；默认模拟模式不真实投递，LIEPIN_SIMULATE=false 才真投）")
                .inputSchema(new McpSchema.JsonSchema("object",
                        Map.of(
                                "jobId", Map.of("type", "integer", "description", "职位 ID（从搜索结果取）"),
                                "jobType", Map.of("type", "string", "description", "职位类型（从搜索结果取，jobKind）")
                        ),
                        List.of("jobId", "jobType"), null, null, null))
                .build();
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    Map<String, Object> a = request.arguments();
                    Object jobIdObj = a.get("jobId");
                    Object jobTypeObj = a.get("jobType");
                    if (!(jobIdObj instanceof Number) || jobTypeObj == null) {
                        return error("参数缺失：jobId(数字) + jobType(字符串) 必填（从搜索结果取）");
                    }
                    long jobId = ((Number) jobIdObj).longValue();
                    String jobType = String.valueOf(jobTypeObj);

                    ApplyLimiter limiter = limiter();   // 单日限次 20（懒加载，跨天重置）
                    if (!limiter.tryAcquire()) {
                        return error("今日投递次数已用完（限 " + MAX_APPLY_PER_DAY + " 次/天），明天再来");
                    }

                    boolean simulate = !"false".equalsIgnoreCase(System.getenv("LIEPIN_SIMULATE"));
                    try {
                        if (simulate) {
                            // 模拟模式：不调官方接口，返回完整"应发生的结果"话术
                            return ok("已投递（模拟，未真实投递）: jobId=" + jobId + " jobType=" + jobType
                                    + "\n[演示模式] 真实投递会：调官方接口 + 间隔 3~8s + 单日限次 20，"
                                    + "部署时将 LIEPIN_SIMULATE 置 false 即真投"
                                    + "\nHR 回复会通过猎聘 App/短信通知，请回 App 查看。");
                        }
                        // 真实模式：随机间隔 3~8s（防官方 60 次/分钟限流）
                        long delay = MIN_APPLY_INTERVAL_MS
                                + (long) (Math.random() * (MAX_APPLY_INTERVAL_MS - MIN_APPLY_INTERVAL_MS));
                        Thread.sleep(delay);
                        JsonNode r = mapper.readTree(runner.run("job", "apply",
                                "--job-id", String.valueOf(jobId),
                                "--job-kind", jobType, "--output", "json"));
                        if (r.path("errCode").asInt() != 0) {
                            return error("投递失败: " + r.path("data").path("result").asText());
                        }
                        return ok("已投递: jobId=" + jobId + "（剩余今日额度 " + limiter.remaining() + " 次）"
                                + "\nHR 回复会通过猎聘 App/短信通知，请回 App 查看。");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return error("投递被中断");
                    } catch (Exception e) {
                        return error("投递失败: " + e.getMessage());
                    }
                })
                .build();
    }

    private static volatile ApplyLimiter applyLimiter;

    // 单例限次器（工具 handler 是静态的，进程内共享一个计数）
    private static ApplyLimiter limiter() {
        if (applyLimiter == null) {
            synchronized (LiepinMcpServer.class) {
                if (applyLimiter == null) {
                    applyLimiter = new ApplyLimiter(MAX_APPLY_PER_DAY);
                }
            }
        }
        return applyLimiter;
    }

    // 共用：拼命令 → 跑 CLI → 解析成 data 节点（list 或 result 的容器）
    private static JsonNode doSearch(LiepinCliRunner runner, String keyword, String address, int salary, int page) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of(
                "job", "search", "--job-name", keyword, "--output", "json"));
        if (!address.isBlank()) cmd.addAll(List.of("--address", address));
        if (salary > 0) cmd.addAll(List.of("--salary-floor", String.valueOf(salary)));
        cmd.addAll(List.of("--page", String.valueOf(page)));

        JsonNode r = mapper.readTree(runner.run(cmd.toArray(new String[0])));
        if (r.path("errCode").asInt() != 0) {
            throw new RuntimeException("猎聘返回错误: " + r.path("data").path("result").asText());
        }
        return r.path("data");
    }

    private static String formatJob(JsonNode j) {
        return "jobId=" + j.path("jobId").asLong()
                + " jobType=" + j.path("jobType").asText("")
                + " | " + j.path("jobName").asText("")
                + " | " + j.path("company").asText("")
                + " | " + j.path("salary").asText("")
                + " | " + j.path("education").asText("")
                + " | " + j.path("workYears").asText("")
                + " | " + j.path("location").asText("");
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