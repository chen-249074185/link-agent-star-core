package com.star.rag.upload;

import com.star.rag.document.ContentCleaner;
import com.star.rag.document.DocumentParser;
import com.star.rag.document.TextChunker;
import com.star.rag.embedding.EmbeddingClient;
import com.star.rag.store.VectorStore;

import java.util.ArrayList;
import java.util.List;

// 上传管道：把已造好的零件（解析/清洗/切片/向量化/入库）串成一条全自动流水线
//   文件 → ①安全校验 → ②解析 → ③清洗 → ④切片 → ⑤批量向量化 → ⑥逐块入库 → "已入库 N 块"
// 失败回滚：任一块入库失败，清掉本次已入库的块，库回到上传前状态，不留半截数据
public class UploadService {

    private static final int EMBED_BATCH = 10;   // 批量向量化 batch 上限（对齐 EmbeddingClient 限制，text-embedding-v3 实测≤10）
    private static final int MAX_FILE_BYTES = 50 * 1024 * 1024;   // 单文件 50MB 上限（10-01 安全加固，11-02 随小程序上传 1MB→50MB 调整）
    private static final List<String> ALLOWED_TYPES = List.of("pdf", "txt", "md", "markdown");  // 类型白名单

    private final DocumentParser parser;
    private final ContentCleaner cleaner;
    private final TextChunker chunker;
    private final EmbeddingClient embedding;
    private final VectorStore store;

    public UploadService(DocumentParser parser, ContentCleaner cleaner, TextChunker chunker,
                         EmbeddingClient embedding, VectorStore store) {
        this.parser = parser;
        this.cleaner = cleaner;
        this.chunker = chunker;
        this.embedding = embedding;
        this.store = store;
    }

    // 上传一个文件：全部完成才算成功；任一步失败自动回滚已入库的块并抛异常
    public UploadResult upload(String userId, String kbId, String filename, byte[] content) {
        String safeName = sanitizeFilename(filename);   // ① 文件名清洗：防路径穿越（../、/ 路径成分剥掉）
        checkSize(content);                             // ② 大小校验：超 10MB 拒绝
        String ext = DocumentParser.extension(safeName);
        if (!ALLOWED_TYPES.contains(ext)) {             // ③ 类型白名单：只收 pdf/txt/md（fail fast，报错清晰）
            throw new IllegalArgumentException("不支持的文件类型: ." + ext + "（仅支持 pdf/txt/md）");
        }

        String raw = parser.parse(safeName, content);   // ④ 解析：文件 → 文字（不支持类型这里就抛）
        String cleaned = cleaner.clean(raw);            // ⑤ 清洗：去脏
        List<String> chunks = chunker.chunk(cleaned);   // ⑥ 切片：整篇 → 若干语义完整小块
        if (chunks.isEmpty()) {
            return new UploadResult(0, "文档无有效内容");
        }

        // ⑦ 批量向量化：在入库前全做完——这里失败则一块都没入库，无需回滚
        List<float[]> vecs = embedding.embedBatch(chunks, EMBED_BATCH);

        // ⑧ 逐块入库：记录行号，供失败回滚
        List<Integer> inserted = new ArrayList<>();
        try {
            for (int i = 0; i < chunks.size(); i++) {
                int rowId = store.insertChunk(userId, kbId, chunks.get(i), vecs.get(i), "{}");
                inserted.add(rowId);
            }
        } catch (Exception e) {
            store.deleteChunks(userId, inserted);          // 失败回滚：清掉本次已入库的块，不留半截
            throw new RuntimeException("上传失败已回滚，共清理 " + inserted.size() + " 块: " + e.getMessage(), e);
        }
        return new UploadResult(inserted.size(), "已入库 " + inserted.size() + " 块");
    }

    // 文件名清洗：只留最后一段路径成分（../、子目录全剥掉），防路径穿越攻击
    // 例：../../etc/notes.txt → notes.txt；.. 或 . 或空 → 拒绝
    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        String name = filename.replace('\\', '/');          // 统一分隔符（Windows \ 也算路径）
        int slash = name.lastIndexOf('/');
        String last = slash >= 0 ? name.substring(slash + 1) : name;
        if (last.isBlank() || last.equals(".") || last.equals("..")) {
            throw new IllegalArgumentException("非法文件名: " + filename);
        }
        return last;
    }

    // 大小校验：超 50MB 直接拒（防病态大文件把 embedding/入库拖垮）
    private void checkSize(byte[] content) {
        if (content != null && content.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("文件超过 50MB 上限: " + content.length + " 字节");
        }
    }

    // 上传结果：成功入库多少块 + 人话
    public record UploadResult(int chunkCount, String message) {
    }
}
