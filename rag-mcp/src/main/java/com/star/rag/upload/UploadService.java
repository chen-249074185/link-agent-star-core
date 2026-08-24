package com.star.rag.upload;

import com.star.rag.document.ContentCleaner;
import com.star.rag.document.DocumentParser;
import com.star.rag.document.TextChunker;
import com.star.rag.embedding.EmbeddingClient;
import com.star.rag.store.VectorStore;

import java.util.ArrayList;
import java.util.List;

// 上传管道：把已造好的零件（解析/清洗/切片/向量化/入库）串成一条全自动流水线
//   文件 → ①解析 → ②清洗 → ③切片 → ④批量向量化 → ⑤逐块入库 → "已入库 N 块"
// 失败回滚：任一块入库失败，清掉本次已入库的块，库回到上传前状态，不留半截数据
public class UploadService {

    private static final int EMBED_BATCH = 25;   // 批量向量化 batch 上限（对齐 EmbeddingClient 限制）

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
        String raw = parser.parse(filename, content);      // ① 解析：文件 → 文字（不支持类型这里就抛）
        String cleaned = cleaner.clean(raw);               // ② 清洗：去脏
        List<String> chunks = chunker.chunk(cleaned);      // ③ 切片：整篇 → 若干语义完整小块
        if (chunks.isEmpty()) {
            return new UploadResult(0, "文档无有效内容");
        }

        // ④ 批量向量化：在入库前全做完——这里失败则一块都没入库，无需回滚
        List<float[]> vecs = embedding.embedBatch(chunks, EMBED_BATCH);

        // ⑤ 逐块入库：记录行号，供失败回滚
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

    // 上传结果：成功入库多少块 + 人话
    public record UploadResult(int chunkCount, String message) {
    }
}
