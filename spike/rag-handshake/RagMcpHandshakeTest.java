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

// RAG MCP 端到端握手：拉起 rag-mcp fat jar 子进程 → initialize → listTools → 上传 → 检索 → 删库
// 真实调用 DashScope embedding（需要 DASHSCOPE_API_KEY 环境变量）
public class RagMcpHandshakeTest {

    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("需要 DASHSCOPE_API_KEY");
            System.exit(1);
        }

        McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());

        // 1. 子进程参数：java -jar rag-mcp fat jar（工作目录 = jar 所在目录，vec0.dll 在那）
        ServerParameters params = ServerParameters.builder("java")
                .args("-jar", "D:/星链智核/agent/rag-mcp/target/rag-mcp-0.0.1-SNAPSHOT.jar")
                .build();
        StdioClientTransport transport = new StdioClientTransport(params, jsonMapper);
        transport.setStdErrorHandler(System.err::println);   // 子进程日志转发出来

        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(60))
                .build();
        try {
            // 2. 握手：initialize → listTools
            client.initialize();
            List<String> toolNames = client.listTools().tools().stream()
                    .map(McpSchema.Tool::name)
                    .toList();
            System.out.println("[1] 工具清单: " + toolNames);

            // 3. 上传 3 条城市知识
            String[][] docs = {
                    {"北京互联网公司集中在西二旗和后厂村", "北京"},
                    {"上海张江高科聚集了大量芯片企业", "上海"},
                    {"杭州滨江是阿里和网易的总部所在地", "杭州"}
            };
            for (String[] doc : docs) {
                var r = client.callTool(new McpSchema.CallToolRequest("rag_upload",
                        Map.of("user_id", "user1", "kb_id", "city-kb", "content", doc[0])));
                System.out.println("[2] 上传[" + doc[1] + "]: " + text(r));
            }

            // 4. 检索：问互联网公司 → 应命中北京那条
            var hit = client.callTool(new McpSchema.CallToolRequest("rag_search",
                    Map.of("user_id", "user1", "kb_id", "city-kb", "query", "哪个城市互联网公司最集中")));
            System.out.println("[3] 检索结果:\n" + text(hit));

            // 5. 隔离验证：user2 搜不到 user1 的库
            var isolated = client.callTool(new McpSchema.CallToolRequest("rag_search",
                    Map.of("user_id", "user2", "kb_id", "city-kb", "query", "互联网公司")));
            System.out.println("[4] user2 检索(user1 的库): " + text(isolated));

            // 6. 删库
            var del = client.callTool(new McpSchema.CallToolRequest("rag_delete_kb",
                    Map.of("user_id", "user1", "kb_id", "city-kb")));
            System.out.println("[5] 删库: " + text(del));

            client.closeGracefully();
            System.out.println("\n===== RAG MCP 端到端验证通过! =====");
        } finally {
            client.close();
        }
    }

    private static String text(McpSchema.CallToolResult r) {
        return ((McpSchema.TextContent) r.content().get(0)).text();
    }
}