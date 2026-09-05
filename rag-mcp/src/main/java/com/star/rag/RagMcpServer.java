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
    // vec0 扩展文件名：Windows=vec0.dll，Linux 容器=vec0.so（Docker 部署用环境变量 VEC0_EXT 指定，本机默认不变）
    private static final String VEC_DLL = System.getenv().getOrDefault("VEC0_EXT", "vec0.dll");

    // 地理编码 HTTP 客户端（共享，避免每次调用 new）
    private static final java.net.http.HttpClient GEO_HTTP = java.net.http.HttpClient.newHttpClient();

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
                            listFilesTool(store, guard, kbRepo),
                            deleteFileTool(store, guard, kbRepo),
                            deleteTool(store, guard, kbRepo),
                            transferTool(store, guard, kbRepo),
                            geocodeTool(store),
                            adminListAllTool(kbRepo),
                            adminListFilesTool(store, kbRepo),
                            adminDeleteFileTool(store, kbRepo),
                            adminDeleteTool(kbRepo)
                    )
                    .build();
            System.err.println("rag-mcp-server 就绪，等待客户端...");
            // 共享房源库（11-03 方案1）：启动时若为空，后台从 rag_data/houses/*.txt 灌入
            // 房源=通用公开数据，所有用户可检索；不阻塞握手（灌 34 城需几十秒真实 embedding）
            new Thread(() -> ensureSharedHouses(store, dataDir, uploader), "shared-houses-init").start();
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
                    if (SQLiteVecStore.isSharedKb(kbId) && !userId.equals(SQLiteVecStore.SHARED_OWNER)) {
                        return errorResult("共享房源库只读：普通用户请上传到自己的私有知识库（房源由采集/管理端维护）");
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

    // 工具4（分文件删除）：列出库内文件（filename | 块数 | 最后入库时间）——文件级列表/删除的前半截
    private static SyncToolSpecification listFilesTool(VectorStore store, KnowledgeBaseGuard guard,
                                                       KnowledgeBaseRepository kbRepo) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("rag_list_files")
                .description("列出指定用户知识库内的全部文件（文件名 | 切片块数 | 最后入库时间）")
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
                    List<VectorStore.FileInfo> files = store.listFiles(userId, kbId);
                    StringBuilder sb = new StringBuilder();
                    for (VectorStore.FileInfo f : files) {
                        sb.append(f.filename()).append(" | ").append(f.chunkCount()).append(" 块 | ")
                                .append(f.lastTime() == null ? "" : f.lastTime()).append("\n");
                    }
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(new McpSchema.TextContent(
                                    sb.isEmpty() ? "知识库暂无文件" : sb.toString().trim())))
                            .build();
                })
                .build();
    }

    // 工具5（分文件删除）：按文件名删除库内全部切片（其他文件不动）；归属校验：不是自己的库不能删
    private static SyncToolSpecification deleteFileTool(VectorStore store, KnowledgeBaseGuard guard,
                                                        KnowledgeBaseRepository kbRepo) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("rag_delete_file")
                .description("删除指定用户知识库内的单个文件及其全部切片（同名文件重复上传的块一起删）")
                .inputSchema(new McpSchema.JsonSchema("object",
                        Map.of(
                                "user_id", Map.of("type", "string"),
                                "kb_id", Map.of("type", "string"),
                                "filename", Map.of("type", "string")
                        ),
                        List.of("user_id", "kb_id", "filename"), null, null, null))
                .build();
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String userId = String.valueOf(args.get("user_id"));
                    String kbId = String.valueOf(args.get("kb_id"));
                    String filename = String.valueOf(args.get("filename"));
                    kbRepo.ensureUserWithDefaultKb(userId);
                    String denied = guard.checkAccess(userId, kbId);
                    if (denied != null) {
                        return errorResult(denied);
                    }
                    if (SQLiteVecStore.isSharedKb(kbId)) {
                        return errorResult("共享房源库只读：普通用户不能删除共享库文件（由采集/管理端维护）");
                    }
                    int deleted = store.deleteFile(userId, kbId, filename);
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(new McpSchema.TextContent(
                                    deleted > 0 ? "已删除文件: " + filename + "（" + deleted + " 块）"
                                            : "未找到文件: " + filename)))
                            .build();
                })
                .build();
    }

    // 工具6：删除某知识库（含库内全部切片）；归属校验：不是自己的库不能删
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
                    if (SQLiteVecStore.isSharedKb(kbId)) {
                        return errorResult("共享房源库只读：普通用户不能删除共享库（由采集/管理端维护）");
                    }
                    kbRepo.deleteKb(userId, kbId);   // 删库 + 库内全部切片
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(new McpSchema.TextContent("已删除知识库: " + kbId)))
                            .build();
                })
                .build();
    }

    // 工具7：账号合并——from 用户的全部知识库并入 to 用户（默认库内容并入 to 默认库，非默认库改归属）
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

    // 工具8（内部）：地址→经纬度地理编码，带 geo_cache 缓存（避免每次通勤测算重复调高德）
    // 前缀 rag_internal_：主程序 McpSkillRegistrar 识别跳过，不注册给 LLM（CommuteSkill 内部直连调用）
    private static SyncToolSpecification geocodeTool(SQLiteVecStore store) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("rag_internal_geocode")
                .description("内部工具：地址转经纬度（查缓存→高德地理编码→写缓存）")
                .inputSchema(new McpSchema.JsonSchema("object",
                        Map.of("address", Map.of("type", "string")),
                        List.of("address"), null, null, null))
                .build();
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    String address = String.valueOf(request.arguments().get("address"));
                    if (address.isBlank()) {
                        return errorResult("请提供地址");
                    }
                    try {
                        String cached = store.getGeoCache(address);
                        if (cached != null) {
                            return McpSchema.CallToolResult.builder()
                                    .content(List.of(new McpSchema.TextContent(cached)))
                                    .build();
                        }
                        String amapKey = System.getenv("AMAP_KEY");
                        if (amapKey == null || amapKey.isBlank()) {
                            return errorResult("高德 Key 未配置");
                        }
                        String location = geocode(address, amapKey);
                        if (location == null) {
                            return errorResult("未能定位地址: " + address);
                        }
                        store.putGeoCache(address, location);
                        return McpSchema.CallToolResult.builder()
                                .content(List.of(new McpSchema.TextContent(location)))
                                .build();
                    } catch (Exception e) {
                        System.err.println("[rag_internal_geocode] 失败: " + e.getMessage());
                        return errorResult("地理编码失败: " + e.getMessage());
                    }
                })
                .build();
    }

    // 高德地理编码：v3/geocode/geo 地址→经纬度（返回 "经度,纬度"）
    private static String geocode(String address, String amapKey) throws Exception {
        String url = "https://restapi.amap.com/v3/geocode/geo?key=" + amapKey
                + "&address=" + java.net.URLEncoder.encode(address, StandardCharsets.UTF_8);
        var resp = GEO_HTTP.send(java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(url))
                        .GET()
                        .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            return null;
        }
        tools.jackson.databind.JsonNode root = new tools.jackson.databind.ObjectMapper().readTree(resp.body());
        if (!"1".equals(root.path("status").asText())) {
            return null;
        }
        var geocodes = root.path("geocodes");
        if (!geocodes.isArray() || geocodes.isEmpty()) {
            return null;
        }
        String location = geocodes.path(0).path("location").asText();
        return location.isEmpty() ? null : location;
    }

    // 工具9（管理端）：列出全部用户的知识库——超管全库视角，不做归属过滤
    // 命名约定 rag_admin_ 前缀：主程序 McpSkillRegistrar 识别并跳过，绝不注册给 LLM 当技能（防越权调用）
    private static SyncToolSpecification adminListAllTool(KnowledgeBaseRepository kbRepo) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("rag_admin_list_all")
                .description("管理端：列出全部用户的知识库（kb_id | user_id | name）")
                .inputSchema(new McpSchema.JsonSchema("object",
                        Map.of(), List.of(), null, null, null))
                .build();
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    List<KnowledgeBaseRepository.KnowledgeBase> kbs = kbRepo.listAll();
                    StringBuilder sb = new StringBuilder();
                    for (KnowledgeBaseRepository.KnowledgeBase kb : kbs) {
                        sb.append(kb.kbId()).append(" | ").append(kb.userId()).append(" | ").append(kb.name()).append("\n");
                    }
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(new McpSchema.TextContent(
                                    sb.isEmpty() ? "暂无知识库" : sb.toString().trim())))
                            .build();
                })
                .build();
    }

    // 工具10（管理端）：列出任意用户库内的文件——超管视角，不做归属过滤
    private static SyncToolSpecification adminListFilesTool(VectorStore store, KnowledgeBaseRepository kbRepo) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("rag_admin_list_files")
                .description("管理端：列出任意用户知识库内的全部文件（文件名 | 切片块数 | 最后入库时间）")
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
                    List<VectorStore.FileInfo> files = store.listFiles(userId, kbId);
                    StringBuilder sb = new StringBuilder();
                    for (VectorStore.FileInfo f : files) {
                        sb.append(f.filename()).append(" | ").append(f.chunkCount()).append(" 块 | ")
                                .append(f.lastTime() == null ? "" : f.lastTime()).append("\n");
                    }
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(new McpSchema.TextContent(
                                    sb.isEmpty() ? "知识库暂无文件" : sb.toString().trim())))
                            .build();
                })
                .build();
    }

    // 工具11（管理端）：删除任意用户库内的单个文件——超管权限，跳过归属校验 + 审计日志（stderr）
    private static SyncToolSpecification adminDeleteFileTool(VectorStore store, KnowledgeBaseRepository kbRepo) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("rag_admin_delete_file")
                .description("管理端：删除任意用户知识库内的单个文件及其全部切片（超管权限，跳过归属校验）")
                .inputSchema(new McpSchema.JsonSchema("object",
                        Map.of(
                                "user_id", Map.of("type", "string"),
                                "kb_id", Map.of("type", "string"),
                                "filename", Map.of("type", "string")
                        ),
                        List.of("user_id", "kb_id", "filename"), null, null, null))
                .build();
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String userId = String.valueOf(args.get("user_id"));
                    String kbId = String.valueOf(args.get("kb_id"));
                    String filename = String.valueOf(args.get("filename"));
                    System.err.println("[审计] 管理员删除文件: userId=" + userId + " kbId=" + kbId + " file=" + filename);
                    int deleted = store.deleteFile(userId, kbId, filename);
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(new McpSchema.TextContent(
                                    deleted > 0 ? "已删除文件: " + filename + "（" + deleted + " 块）"
                                            : "未找到文件: " + filename)))
                            .build();
                })
                .build();
    }

    // 工具12（管理端）：删除任意用户的知识库——超管权限，跳过归属校验（普通 delete 工具过 guard 会拒他人库）
    // 审计日志走 stderr（stdio 协议铁律：stdout 只允许 JSON-RPC 消息）
    private static SyncToolSpecification adminDeleteTool(KnowledgeBaseRepository kbRepo) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("rag_admin_delete_kb")
                .description("管理端：删除任意用户的某个知识库及其全部内容（超管权限，跳过归属校验）")
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
                    System.err.println("[审计] 管理员删除知识库: userId=" + userId + " kbId=" + kbId);
                    kbRepo.deleteKb(userId, kbId);   // 删库 + 库内全部切片（无归属校验）
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(new McpSchema.TextContent("已删除知识库: " + kbId + "（用户 " + userId + "）")))
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

    // 共享房源库初始化：空库时把 rag_data/houses/*.txt 全量向量化入库（system 身份）
    // 幂等：已有内容跳过（防重启重复灌入）；单个城市失败跳过不中断
    private static void ensureSharedHouses(SQLiteVecStore store, Path dataDir, UploadService uploader) {
        try {
            if (!store.listFiles(SQLiteVecStore.SHARED_OWNER, SQLiteVecStore.SHARED_KB_ID).isEmpty()) {
                System.err.println("共享房源库已有内容，跳过自动灌入");
                return;
            }
            Path housesDir = dataDir.resolve("houses");
            if (!Files.isDirectory(housesDir)) {
                System.err.println("共享房源库：未找到 rag_data/houses 目录，跳过自动灌入");
                return;
            }
            List<Path> files;
            try (var s = Files.list(housesDir)) {
                files = s.filter(p -> p.getFileName().toString().endsWith(".txt")).sorted().toList();
            }
            if (files.isEmpty()) {
                System.err.println("共享房源库：rag_data/houses 无 txt 文件，跳过自动灌入");
                return;
            }
            int total = 0;
            for (Path f : files) {
                try {
                    UploadService.UploadResult r = uploader.upload(
                            SQLiteVecStore.SHARED_OWNER, SQLiteVecStore.SHARED_KB_ID,
                            f.getFileName().toString(), Files.readAllBytes(f));
                    total += r.chunkCount();
                } catch (Exception e) {
                    System.err.println("共享房源库灌入失败（跳过）: " + f.getFileName() + " " + e.getMessage());
                }
            }
            System.err.println("共享房源库就绪: " + files.size() + " 个城市 " + total + " 块");
        } catch (Exception e) {
            System.err.println("共享房源库初始化失败: " + e.getMessage());
        }
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
