-- RAG 知识库表结构（SQLite，每用户私有）
-- 运行方式：由 RagMcpServer 启动时自动执行（不存在则建）

-- 向量库：vec0 虚拟表（sqlite-vec 扩展提供，KNN 检索）
-- user_id 作 partition key：不同用户的向量分区存储，检索时自动只扫本区（私有库隔离）
CREATE VIRTUAL TABLE IF NOT EXISTS vec_chunks USING vec0(
    user_id TEXT partition key,
    kb_id TEXT,
    chunk_embedding float[1024]
);

-- 原文表：向量表只存向量+归属，正文/元数据存这里（向量表不支持长文本列）
CREATE TABLE IF NOT EXISTS chunks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    kb_id TEXT NOT NULL,              -- 所属知识库（每个用户可多个库）
    user_id TEXT NOT NULL,            -- 归属用户（隔离校验依据）
    content TEXT NOT NULL,            -- 切片原文
    embedding TEXT NOT NULL,          -- 向量 JSON 串（[0.1, 0.2, ...] 1024 维）
    metadata_json TEXT,               -- 来源文档/页码等元数据
    created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);

CREATE INDEX IF NOT EXISTS idx_chunks_user ON chunks (user_id, kb_id);