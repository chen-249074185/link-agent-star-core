package com.star.rag;

import com.star.rag.document.ContentCleaner;
import com.star.rag.document.DocumentParser;
import com.star.rag.document.TextChunker;
import com.star.rag.embedding.EmbeddingClient;
import com.star.rag.security.KnowledgeBaseGuard;
import com.star.rag.store.KnowledgeBaseRepository;
import com.star.rag.store.SQLiteVecStore;
import com.star.rag.store.VectorStore;
import com.star.rag.upload.UploadService;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import tools.jackson.databind.json.JsonMapper;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProvider;

import java.nio.charset.StandardCharsets;

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

        // 数据目录：优先环境变量 RAG_DATA_DIR（主程序指定稳定目录，如 运行目录/rag_data），
        // 缺省回退 jar 同目录——但 target/ 是构建产物会被 clean 删除，正式运行必须显式指定！
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
        String dataDirEnv = System.getenv("RAG_DATA_DIR");
        Path dataDir = dataDirEnv != null && !dataDirEnv.isBlank() ? Path.of(dataDirEnv) : jarDir;
        Path dbPath = dataDir.resolve(DB_PATH);
        // vec0.dll 是运行时依赖（native 库，被 mvn clean 会删，git 管理的稳定副本在模块根）：
        // 按 数据目录 → jar 目录 → 模块根（jar 的父目录）三级找，找到后复制到数据目录
        Path vecDll = dataDir.resolve(VEC_DLL);
        try {
            Files.createDirectories(dataDir);
            Path[] candidates = {jarDir.resolve(VEC_DLL), jarDir.getParent().resolve(VEC_DLL)};
            if (!Files.exists(vecDll)) {
                for (Path candidate : candidates) {
                    if (Files.exists(candidate)) {
                        Files.copy(candidate, vecDll);
                        break;
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("初始化数据目录失败: " + e.getMessage());
            System.exit(1);
            return;
        }
        if (!Files.exists(vecDll)) {
            System.err.println("找不到 vec0.dll（数据目录/jar 目录/模块根都没有）");
            System.exit(1);
            return;
        }
        System.err.println("数据目录: " + dataDir);

        McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());
        EofAwareInputStream eofAware = new EofAwareInputStream(System.in);
        McpServerTransportProvider transport = new StdioServerTransportProvider(jsonMapper, eofAware, System.out);
        System.setOut(System.err);   // 之后日志全走 stderr，不污染协议流

        try {
            SQLiteVecStore store = new SQLiteVecStore(dbPath.toString(), vecDll.toString());
            KnowledgeBaseRepository kbRepo = store;   // 同一连接：user/知识库归属显式管理
            EmbeddingClient embedding = new EmbeddingClient(apiKey);
            KnowledgeBaseGuard guard = new KnowledgeBaseGuard(store);
            // 上传管道：解析→清洗→切片→向量化→入库（失败自动回滚）
            UploadService uploader = new UploadService(
                    new DocumentParser(), new ContentCleaner(), new TextChunker(), embedding, store);

McpServer.sync(transport)
                    .serverInfo("rag-mcp-server", "0.1.0")
                    .tools(
                            uploadTool(uploader, guard, kbRepo),
                            searchTool(store, embedding, guard, kbRepo),
                            listKbsTool(kbRepo),
                            deleteTool(store, guard, kbRepo),
                            transferTool(store, guard, kbRepo)
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
    // 归属校验：不是自己的库拒收；失败已由 UploadService 内部回滚
    // is_base64=true 时 content 按 Base64 解码成字节（HTTP 上传二进制 PDF 走这条路，JSON 传不了裸二进制）
    private static SyncToolSpecification uploadTool(UploadService uploader, KnowledgeBaseGuard guard,
                                                    KnowledgeBaseRepository kbRepo) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("rag_upload")
                .description("上传一个文本文档到指定用户的私有知识库，自动解析/清洗/切片/向量化/入库")
                .inputSchema(new McpSchema.JsonSchema("object",
                        Map.of(
                                "user_id", Map.of("type", "string"),
                                "kb_id", Map.of("type", "string"),
                                "filename", Map.of("type", "string"),
                                "content", Map.of("type", "string"),
                                "is_base64", Map.of("type", "boolean", "description", "content 是否为 Base64 编码（传二进制文件用）")
                        ),
                        List.of("user_id", "kb_id", "filename", "content"), null, null, null))
                .build();
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String userId = String.valueOf(args.get("user_id"));
                    String kbId = String.valueOf(args.get("kb_id"));
                    kbRepo.ensureUserWithDefaultKb(userId);          // 新用户自动建默认库
                    String denied = guard.checkAccess(userId, kbId);   // 归属校验（R10）
                    if (denied != null) {
                        return errorResult(denied);
                    }
                    String filename = String.valueOf(args.get("filename"));
                    String rawContent = String.valueOf(args.get("content"));
                    boolean isBase64 = Boolean.parseBoolean(String.valueOf(args.getOrDefault("is_base64", "false")));
                    byte[] bytes = isBase64
                            ? java.util.Base64.getDecoder().decode(rawContent)
                            : rawContent.getBytes(StandardCharsets.UTF_8);
                    UploadService.UploadResult result = uploader.upload(userId, kbId, filename, bytes);
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(new McpSchema.TextContent(result.message())))
                            .build();
                })
                .build();
    }

    // 工具2：检索知识库（topK 最相关切片）；归属校验：越权直接拒，不悄悄返回空
    private static SyncToolSpecification searchTool(VectorStore store, EmbeddingClient embedding,
                                                    KnowledgeBaseGuard guard, KnowledgeBaseRepository kbRepo) {
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
                    kbRepo.ensureUserWithDefaultKb(userId);
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

    // 工具3：列出用户全部知识库（HTTP 管理接口 /api/rag/list 用；顺带保证新用户有默认库）
    private static SyncToolSpecification listKbsTool(KnowledgeBaseRepository kbRepo) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("rag_list_kbs")
                .description("列出指定用户的全部知识库")
                .inputSchema(new McpSchema.JsonSchema("object",
                        Map.of("user_id", Map.of("type", "string")),
                        List.of("user_id"), null, null, null))
                .build();
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    String userId = String.valueOf(request.arguments().get("user_id"));
                    kbRepo.ensureUserWithDefaultKb(userId);
                    List<KnowledgeBaseRepository.KnowledgeBase> kbs = kbRepo.listByUser(userId);
                    StringBuilder sb = new StringBuilder();
                    for (KnowledgeBaseRepository.KnowledgeBase kb : kbs) {
                        sb.append(kb.kbId()).append(" | ").append(kb.name()).append("\n");
                    }
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(new McpSchema.TextContent(
                                    sb.isEmpty() ? "暂无知识库" : sb.toString().trim())))
                            .build();
                })
                .build();
    }

    // 工具4：删除某知识库（含库内全部切片）；归属校验：不是自己的库不能删
    private static SyncToolSpecification deleteTool(VectorStore store, KnowledgeBaseGuard guard,
                                                    KnowledgeBaseRepository kbRepo) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("rag_delete_kb")
                .description("删除指定用户的某个知识库及其全部内容")
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
                    kbRepo.ensureUserWithDefaultKb(userId);
                    String denied = guard.checkAccess(userId, kbId);
                    if (denied != null) {
                        return errorResult(denied);
                    }
                    kbRepo.deleteKb(userId, kbId);   // 删库 + 库内全部切片
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(new McpSchema.TextContent("已删除知识库: " + kbId)))
                            .build();
                })
                .build();
    }

    // 工具5：账号合并——from 用户的全部知识库并入 to 用户（默认库内容并入 to 默认库，非默认库改归属）
    // 主程序账号合并（bind-email 触发）用；迁移失败抛异常 → 主程序事务回滚不删被并入账号（补偿语义）
    private static SyncToolSpecification transferTool(VectorStore store, KnowledgeBaseGuard guard,
                                                      KnowledgeBaseRepository kbRepo) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("rag_transfer_kb")
                .description("账号合并：把某用户的全部知识库内容并入另一用户（默认库内容并入对方默认库）")
                .inputSchema(new McpSchema.JsonSchema("object",
                        Map.of(
                                "user_id", Map.of("type", "string", "description", "被并入方（将删除的知识库身份）"),
                                "to_user_id", Map.of("type", "string", "description", "接收方（保留的账号）")
                        ),
                        List.of("user_id", "to_user_id"), null, null, null))
                .build();
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String fromUserId = String.valueOf(args.get("user_id"));
                    String toUserId = String.valueOf(args.get("to_user_id"));
                    if (fromUserId.equals(toUserId)) {
                        return errorResult("合并双方不能是同一用户");
                    }
                    kbRepo.transferTo(fromUserId, toUserId);
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(new McpSchema.TextContent(
                                    "知识库已迁移: " + fromUserId + " → " + toUserId)))
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
