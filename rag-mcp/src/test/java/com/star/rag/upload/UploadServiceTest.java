package com.star.rag.upload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.star.rag.document.ContentCleaner;
import com.star.rag.document.DocumentParser;
import com.star.rag.document.TextChunker;
import com.star.rag.embedding.EmbeddingClient;
import com.star.rag.store.SQLiteVecStore;
import com.star.rag.store.VectorStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// UploadService 集成测试：全链路（真实 store + 假 embedding 服务器）+ 失败回滚
class UploadServiceTest {

    // 指向 spike 里已下载的 vec0.dll（与 SQLiteVecStoreTest 一致）
    private static final String VEC_DLL = "spike/sqlite-vec/vec0.dll";

    private final ObjectMapper mapper = new ObjectMapper();

    private HttpServer embedServer;   // 假 embedding 服务器
    private String embedUrl;
    private SQLiteVecStore store;
    private UploadService service;

    @BeforeEach
    void setUp() throws Exception {
        // 假 embedding 服务器：input n 条 → 返回 n 条 1024 维向量（第 i 条第一维 = i+1）
        embedServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        embedServer.createContext("/v1/embeddings", exchange -> {
            JsonNode req = mapper.readTree(exchange.getRequestBody().readAllBytes());
            int n = req.path("input").size();
            byte[] resp = embeddingResponse(n).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        embedServer.start();
        embedUrl = "http://127.0.0.1:" + embedServer.getAddress().getPort() + "/v1/embeddings";

        store = new SQLiteVecStore(":memory:", VEC_DLL);
        service = new UploadService(
                new DocumentParser(),
                new ContentCleaner(),
                new TextChunker(),
                new EmbeddingClient("test-key", embedUrl),
                store);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (embedServer != null) embedServer.stop(0);
        if (store != null) store.close();
    }

    // 全链路：txt 上传 → 自动切片入库 → 检索能命中
    @Test
    void upload_txt_runsFullPipeline() {
        String doc = "北京租房通勤分析。\n\n上海租房生活成本。\n\n广州互联网公司集中。";
        UploadService.UploadResult r = service.upload("u1", "kb1", "笔记.txt", doc.getBytes(StandardCharsets.UTF_8));

        assertEquals(3, r.chunkCount());                  // 3 段 → 3 块
        assertTrue(r.message().contains("3"));

        // 检索验证：查第 0 块向量（第一维=1）→ 应命中"北京"那段
        float[] query = new float[1024];
        query[0] = 1.0f;
        List<VectorStore.SearchHit> hits = store.search("u1", "kb1", query, 5, 1.5);
        assertTrue(!hits.isEmpty());
        assertTrue(hits.get(0).content().contains("北京"), "应命中北京段，实际: " + hits.get(0).content());
    }

    // 无有效内容（空文档）→ 0 块，不抛错
    @Test
    void upload_blankDoc_returnsZero() {
        UploadService.UploadResult r = service.upload("u1", "kb1", "空.txt", "   \n\n  ".getBytes(StandardCharsets.UTF_8));
        assertEquals(0, r.chunkCount());
        assertTrue(r.message().contains("无有效内容"));
    }

    // 失败回滚：第 2 块入库失败 → 已入库的第 1 块被清掉，库回到上传前状态
    @Test
    void upload_rollbackCleansInsertedChunks() {
        FailingStore failing = new FailingStore(2);   // 第 2 次 insert 抛异常
        UploadService svc = new UploadService(
                new DocumentParser(), new ContentCleaner(), new TextChunker(),
                new EmbeddingClient("test-key", embedUrl), failing);

        String doc = "第一块。\n\n第二块。";           // 2 段 → 2 块
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> svc.upload("u1", "kb1", "笔记.txt", doc.getBytes(StandardCharsets.UTF_8)));

        assertTrue(ex.getMessage().contains("回滚"));
        assertTrue(ex.getMessage().contains("清理 1 块"));    // 只入库成功 1 块被回滚
        assertEquals(List.of(1), failing.deletedRowIds);      // 删的是第 1 块的行号
    }

    // ===== 上传安全加固（10-01 内容提前）=====

    // 路径穿越攻击：../../ 伪装路径被剥掉只剩文件名，上传照常成功（不落盘不碰路径，无攻击面）
    @Test
    void upload_pathTraversalFilename_sanitized() {
        String doc = "安全内容。";
        UploadService.UploadResult r = service.upload("u1", "kb1",
                "../../etc/passwd/../笔记.txt", doc.getBytes(StandardCharsets.UTF_8));

        assertEquals(1, r.chunkCount());                      // 清洗后照常入库
        assertTrue(r.message().contains("1"));
    }

    // 纯路径名（.. 或空）→ 拒绝
    @Test
    void upload_invalidFilename_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.upload("u1", "kb1", "..", "内容".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> service.upload("u1", "kb1", "", "内容".getBytes(StandardCharsets.UTF_8)));
    }

    // 超大文件：超过 50MB 拒绝（不解析不向量化，fail fast）
    @Test
    void upload_oversizedFile_rejected() {
        byte[] big = new byte[50 * 1024 * 1024 + 1];          // 50MB + 1 字节
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.upload("u1", "kb1", "大文件.txt", big));
        assertTrue(ex.getMessage().contains("50MB"));
    }

    // 错误类型：白名单外扩展名拒绝（fail fast，报错比 DocumentParser 更友好）
    @Test
    void upload_unsupportedType_rejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.upload("u1", "kb1", "evil.exe", "内容".getBytes(StandardCharsets.UTF_8)));
        assertTrue(ex.getMessage().contains("不支持的文件类型"));
    }

    // 造 1024 维向量响应：第 i 条第一维 = i+1（与 vec0 表 float[1024] 匹配）
    private String embeddingResponse(int n) {
        StringBuilder sb = new StringBuilder("{\"data\":[");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"index\":").append(i).append(",\"embedding\":[").append(i + 1.0f);
            for (int d = 1; d < 1024; d++) sb.append(",0.0");
            sb.append("]}");
        }
        return sb.append("]}").toString();
    }

    // 手写假 store：第 failAt 次 insertChunk 抛异常，记录已删除的行号（验证回滚调用）
    private static class FailingStore implements VectorStore {

        private final int failAt;
        private int inserts = 0;
        final List<Integer> deletedRowIds = new ArrayList<>();

        FailingStore(int failAt) {
            this.failAt = failAt;
        }

        @Override
        public int insertChunk(String userId, String kbId, String content, String filename, float[] embedding, String metadataJson) {
            int id = ++inserts;
            if (inserts == failAt) {
                throw new RuntimeException("模拟入库失败");
            }
            return id;
        }

        @Override
        public void deleteChunks(String userId, List<Integer> rowIds) {
            deletedRowIds.addAll(rowIds);
        }

        @Override
        public List<SearchHit> search(String userId, String kbId, float[] queryEmbedding, int topK, double threshold) {
            return List.of();
        }

        @Override
        public List<FileInfo> listFiles(String userId, String kbId) {
            return List.of();
        }

        @Override
        public int deleteFile(String userId, String kbId, String filename) {
            return 0;
        }

        @Override
        public int deleteByKb(String userId, String kbId) {
            return 0;
        }

        @Override
        public boolean ownsKb(String userId, String kbId) {
            return true;
        }
    }
}
