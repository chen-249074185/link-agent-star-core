import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

// 猎聘 MCP 壳端到端握手：拉起 liepin-mcp fat jar → doctor 自检 → 搜岗位 → 读简历
// 需要 LIEPIN_USER_TOKEN（.env 里那份；这里手动传入子进程环境变量）
public class LiepinMcpHandshakeTest {

    public static void main(String[] args) throws Exception {
        String token = System.getenv("LIEPIN_USER_TOKEN");
        if (token == null || token.isBlank()) {
            System.err.println("需要 LIEPIN_USER_TOKEN");
            System.exit(1);
        }

        McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());
        ServerParameters params = ServerParameters.builder("java")
                .args("-jar", "D:/星链智核/agent/liepin-mcp/target/liepin-mcp-0.0.1-SNAPSHOT.jar")
                .env(Map.of("LIEPIN_USER_TOKEN", token))
                .build();
        StdioClientTransport transport = new StdioClientTransport(params, jsonMapper);
        transport.setStdErrorHandler(System.err::println);

        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(120))
                .build();
        try {
            client.initialize();
            List<String> toolNames = client.listTools().tools().stream()
                    .map(McpSchema.Tool::name)
                    .toList();
            System.out.println("[1] 工具清单: " + toolNames);

            var doctor = client.callTool(new McpSchema.CallToolRequest("liepin_doctor", Map.of()));
            System.out.println("[2] doctor 自检:\n" + text(doctor));

            var search = client.callTool(new McpSchema.CallToolRequest("liepin_search_jobs",
                    Map.of("keyword", "Java开发", "address", "北京", "page", 0)));
            System.out.println("[3] 搜索(北京 Java开发):\n" + text(search));

            var filter = client.callTool(new McpSchema.CallToolRequest("liepin_filter_jobs",
                    Map.of("keyword", "Java开发", "address", "北京")));
            System.out.println("[4] 精筛(按画像: 本科/经验不限应届实习/薪资≥10k/城市):\n" + text(filter));

            var dryRun = client.callTool(new McpSchema.CallToolRequest("liepin_apply_dry_run",
                    Map.of("jobs", "[{\"jobId\":85116009,\"jobType\":\"2\",\"jobName\":\"Java开发工程师\",\"company\":\"示例公司\"}]")));
            System.out.println("[5] 投递计划 dry-run:\n" + text(dryRun));

            var apply = client.callTool(new McpSchema.CallToolRequest("liepin_apply",
                    Map.of("jobId", 999999999, "jobType", "2")));
            System.out.println("[5.5] 投递(真实调用, 不存在的jobId验证链路):\n" + text(apply));

            var resume = client.callTool(new McpSchema.CallToolRequest("liepin_resume", Map.of()));
            System.out.println("[6] 简历:\n" + text(resume));

            client.closeGracefully();
            System.out.println("\n===== 猎聘 MCP 端到端验证通过! =====");
        } finally {
            client.close();
        }
    }

    private static String text(McpSchema.CallToolResult r) {
        return ((McpSchema.TextContent) r.content().get(0)).text();
    }
}