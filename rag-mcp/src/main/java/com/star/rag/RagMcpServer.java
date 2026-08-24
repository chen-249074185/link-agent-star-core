package com.star.rag;

import com.star.rag.document.ContentCleaner;
import com.star.rag.document.DocumentParser;
import com.star.rag.document.TextChunker;
import com.star.rag.embedding.EmbeddingClient;
import com.star.rag.security.KnowledgeBaseGuard;
import com.star.rag.store.SQLiteVecStore;
import com.star.rag.store.VectorStore;
import com.star.rag.upload.UploadService;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

// RAG MCP Server：每用户私有知识库（上传切片入库 + 向量检索 + 删库）
// 传输 = stdio（主进程以子进程方式拉起，通过 stdin/stdout JSON-RPC 对话）
public class RagMcpServer {

    // 数据库文件 + vec0 扩展：默认放 jar 同目录（不依赖进程工作目录，java -jar 任何地方拉起都能找到）
    private static final String DB_PATH = "rag.db";
    private static final String VEC_DLL = "vec0.dll";

    public static void main(String[] args) {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("缺少环境变量 DASHSCOPE_API_KEY");
            System.exit(1);
        }

        // 从 jar 位置推导资源目录：foo.jar 在 X/ 下 → 资源在 X/rag.db 和 X/vec0.dll
        Path jarDir;
        try {
            jarDir = Path.of(RagMcpServer.class.getProtectionDomain()
                            .getCodeSource().getLocation().toURI())
                    .getParent();
        } catch (Exception e) {
            System.err.println("无法定位 jar 目录: " + e.getMessage());
            System.exit(1);
            return;
        }
        Path dbPath = jarDir.resolve(DB_PATH);
        Path vecDll = jarDir.resolve(VEC_DLL);

        McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());
        EofAwareInputStream eofAware = new EofAwareInputStream(System.in);
        McpServerTransportProvider transport = new StdioServerTransportProvider(jsonMapper, eofAware, System.out);
        System.setOut(System.err);   // 之后日志全走 stderr，不污染协议流

        try {
            VectorStore store = new SQLiteVecStore(dbPath.toString(), vecDll.toString());
            EmbeddingClient embedding = new EmbeddingClient(apiKey);
            KnowledgeBaseGuard guard = new KnowledgeBaseGuard(store);
            // 上传管道：解析→清洗→切片→向量化→入库（失败自动回滚）
            UploadService uploader = new UploadService(
                    new DocumentParser(), new ContentCleaner(), new TextChunker(), embedding, store);

            McpServer.sync(transport)
                    .serverInfo("rag-mcp-server", "0.1.0")
                    .tools(
                            uploadTool(uploader, guard),
                            searchTool(store, embedding, guard),
                            deleteTool(store, guard)
                    )
                    .build();
            System.err.println("rag-mcp-server 就绪，等待客户端...");
        } catch (Exception e) {
            System.err.println("启动失败: " + e.getMessage());
            System.exit(1);
        }

        eofAware.awaitEof();
    }

    // 工具1：上传文档（txt/md，filename+content）→ 走完整管道自动切片向量化入库
    // 归属校验：别人占用的库拒收；失败已由 UploadService 内部回滚
    private static SyncToolSpecification uploadTool(UploadService uploader, KnowledgeBaseGuard guard) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("rag_upload")
                .description("上传一个文本文档到指定用户的私有知识库，自动解析/清洗/切片/向量化/入库")
                .inputSchema(new McpSchema.JsonSchema("object",
                        Map.of(
                                "user_id", Map.of("type", "string"),
                                "kb_id", Map.of("type", "string"),
                                "filename", Map.of("type", "string"),
                                "content", Map.of("type", "string")
                        ),
                        List.of("user_id", "kb_id", "filename", "content"), null, null, null))
                .build();
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String userId = String.valueOf(args.get("user_id"));
                    String kbId = String.valueOf(args.get("kb_id"));
                    String denied = guard.checkAccess(userId, kbId);   // 归属校验（R10）
                    if (denied != null) {
                        return errorResult(denied);
                    }
                    String filename = String.valueOf(args.get("filename"));
                    byte[] content = String.valueOf(args.get("content")).getBytes(StandardCharsets.UTF_8);
                    UploadService.UploadResult result = uploader.upload(userId, kbId, filename, content);
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(new McpSchema.TextContent(result.message())))
                            .build();
                })
                .build();
    }

    // 工具2：检索知识库（topK 最相关切片）；归属校验：越权直接拒，不悄悄返回空
    private static SyncToolSpecification searchTool(VectorStore store, EmbeddingClient embedding, KnowledgeBaseGuard guard) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("rag_search")
                .description("在指定用户的私有知识库检索与问题最相关的文本片段")
                .inputSchema(new McpSchema.JsonSchema("object",
                        Map.of(
                                "user_id", Map.of("type", "string"),
                                "kb_id", Map.of("type", "string"),
                                "query", Map.of("type", "string"),
                                "top_k", Map.of("type", "integer")
                        ),
                        List.of("user_id", "kb_id", "query"), null, null, null))
                .build();
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String userId = String.valueOf(args.get("user_id"));
                    String kbId = String.valueOf(args.get("kb_id"));
                    String denied = guard.checkAccess(userId, kbId);
                    if (denied != null) {
                        return errorResult(denied);
                    }
                    String query = String.valueOf(args.get("query"));
                    int topK = args.getOrDefault("top_k", 5) instanceof Number n ? n.intValue() : 5;
                    float[] vec = embedding.embed(query);
                    List<VectorStore.SearchHit> hits = store.search(userId, kbId, vec, topK, 1.5);
                    StringBuilder sb = new StringBuilder();
                    for (VectorStore.SearchHit h : hits) {
                        sb.append("[score=").append(String.format("%.3f", h.score())).append("] ")
                                .append(h.content()).append("\n");
                    }
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(new McpSchema.TextContent(sb.isEmpty() ? "未找到相关片段" : sb.toString())))
                            .build();
                })
                .build();
    }

    // 工具3：删除某知识库全部内容；归属校验：不是自己的库不能删
    private static SyncToolSpecification deleteTool(VectorStore store, KnowledgeBaseGuard guard) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("rag_delete_kb")
                .description("删除指定用户的某个知识库的全部内容")
                .inputSchema(new McpSchema.JsonSchema("object",
                        Map.of(
                                "user_id", Map.of("type", "string"),
                                "kb_id", Map.of("type", "string")
                        ),
                        List.of("user_id", "kb_id"), null, null, null))
                .build();
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String userId = String.valueOf(args.get("user_id"));
                    String kbId = String.valueOf(args.get("kb_id"));
                    String denied = guard.checkAccess(userId, kbId);
                    if (denied != null) {
                        return errorResult(denied);
                    }
                    int deleted = store.deleteByKb(userId, kbId);
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(new McpSchema.TextContent("已删除 " + deleted + " 条")))
                            .build();
                })
                .build();
    }

    // 统一拒绝话术返回（越权/参数错都走这里）
    private static McpSchema.CallToolResult errorResult(String msg) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(msg)))
                .build();
    }

    // EOF 感知流（坑21 的解法，和 DemoEchoServer 一致）：客户端断开 → 主线程退出，不残留僵尸进程
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