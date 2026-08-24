package com.star.rag.store;

import java.util.List;

// 向量检索合同：上层(MCP Server)只认这个接口，不关心底层是 sqlite-vec 还是内存余弦
// D37 决策：spike 验证 sqlite-vec 在 Windows 可加载 → 正式实现 = SQLiteVecStore
// 接口保留的意义：将来要换存储（如切回方案B内存版）只换实现类，MCP 工具零改动
public interface VectorStore {

    // 存一个切片：user_id 归属 + kb_id 所属库 + 正文 + 向量（float 数组）
    void insertChunk(String userId, String kbId, String content, float[] embedding, String metadataJson);

    // 找最相近的 topK 个切片（只搜该用户的私有库，隔离校验在调用方做）
    List<SearchHit> search(String userId, String kbId, float[] queryEmbedding, int topK, double threshold);

    // 删某知识库的全部切片（用户删库用）
    int deleteByKb(String userId, String kbId);

    // 该用户是否"拥有"此知识库（R10 归属校验）：没有其他用户占用同名的 kb_id = 归我
    boolean ownsKb(String userId, String kbId);

    // 检索命中：正文 + 相似度
    record SearchHit(String content, double score) {
    }
}