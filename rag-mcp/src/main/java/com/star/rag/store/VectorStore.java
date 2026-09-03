package com.star.rag.store;

import java.util.List;

// 向量检索合同：上层(MCP Server)只认这个接口，不关心底层是 sqlite-vec 还是内存余弦
// D37 决策：spike 验证 sqlite-vec 在 Windows 可加载 → 正式实现 = SQLiteVecStore
// 接口保留的意义：将来要换存储（如切回方案B内存版）只换实现类，MCP 工具零改动
public interface VectorStore {

    // 存一个切片：user_id 归属 + kb_id 所属库 + 来源文件名 + 正文 + 向量（float 数组）；返回新行号（上传管道回滚用）
    int insertChunk(String userId, String kbId, String content, String filename, float[] embedding, String metadataJson);

    // 按行号批量删切片（上传失败回滚用：清掉本次已入库的块）
    void deleteChunks(String userId, List<Integer> rowIds);

    // 找最相近的 topK 个切片（只搜该用户的私有库，隔离校验在调用方做）
    List<SearchHit> search(String userId, String kbId, float[] queryEmbedding, int topK, double threshold);

    // 列出库内文件（按 filename 分组：块数 + 最后入库时间），分文件删除的列表依据
    List<FileInfo> listFiles(String userId, String kbId);

    // 按文件名删除库内全部切片（分文件删除：删这个文件的所有块，其他文件不动）
    int deleteFile(String userId, String kbId, String filename);

    // 删某知识库的全部切片（用户删库用）
    int deleteByKb(String userId, String kbId);

    // 该用户是否"拥有"此知识库（R10 归属校验）：没有其他用户占用同名的 kb_id = 归我
    boolean ownsKb(String userId, String kbId);

    // 检索命中：正文 + 相似度
    record SearchHit(String content, double score) {
    }

    // 文件条目：来源文件名 + 切片块数 + 最后入库时间（listFiles 的输出）
    record FileInfo(String filename, int chunkCount, String lastTime) {
    }
}