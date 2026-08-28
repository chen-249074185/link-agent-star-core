package com.star.rag.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

// VectorStore 的 sqlite-vec 实现（D37 方案 A）+ KnowledgeBaseRepository（user/知识库显式归属）
// 三块数据：vec_chunks 虚拟表（向量 KNN）+ chunks 普通表（正文/元数据）+ users/knowledge_base（归属管理）
public class SQLiteVecStore implements VectorStore, KnowledgeBaseRepository {

    private final Connection conn;

    // 打开数据库 + 加载 vec0 扩展 + 建表
    public SQLiteVecStore(String dbPath, String vecDllPath) throws Exception {
        Class.forName("org.sqlite.JDBC");          // 显式注册驱动（新版不自动 SPI）
        Properties props = new Properties();
        props.setProperty("enable_load_extension", "true");   // 允许 .load 外部扩展
        conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath, props);

        // spike 已验证：Windows 上 vec0.dll 可正常加载
        try (Statement st = conn.createStatement()) {
            st.execute("SELECT load_extension('" + vecDllPath + "')");
        }
        initSchema();
    }

    // 建表：schema.sql 里的 DDL（幂等：IF NOT EXISTS）
    private void initSchema() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE VIRTUAL TABLE IF NOT EXISTS vec_chunks USING vec0(" +
                    "user_id TEXT partition key, kb_id TEXT, chunk_embedding float[1024])");
            st.execute("CREATE TABLE IF NOT EXISTS chunks (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "kb_id TEXT NOT NULL, user_id TEXT NOT NULL," +
                    "content TEXT NOT NULL, embedding TEXT NOT NULL," +
                    "metadata_json TEXT," +
                    "created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')))");
            st.execute("CREATE INDEX IF NOT EXISTS idx_chunks_user ON chunks (user_id, kb_id)");
            // W8(09-29)：user 表 + knowledge_base 表，知识库归属显式化（R10）
            st.execute("CREATE TABLE IF NOT EXISTS users (" +
                    "user_id TEXT PRIMARY KEY," +
                    "created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')))");
            st.execute("CREATE TABLE IF NOT EXISTS knowledge_base (" +
                    "kb_id TEXT PRIMARY KEY," +
                    "user_id TEXT NOT NULL," +
                    "name TEXT NOT NULL," +
                    "created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')))");
        }
    }

    // 存切片：正文/向量进 chunks 表，向量进 vec_chunks 虚拟表（rowid 对应）；返回新行号（回滚追踪用）
    @Override
    public int insertChunk(String userId, String kbId, String content, float[] embedding, String metadataJson) {
        try {
            String vecJson = toJson(embedding);
            // 先插普通表拿自增 id（rowid 就是 vec 表的 rowid）
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO chunks (kb_id, user_id, content, embedding, metadata_json) VALUES (?,?,?,?,?)");
            ps.setString(1, kbId);
            ps.setString(2, userId);
            ps.setString(3, content);
            ps.setString(4, vecJson);
            ps.setString(5, metadataJson);
            ps.executeUpdate();
            int rowId = ps.getGeneratedKeys().getInt(1);
            ps.close();

            // 再插虚拟表：user_id 作 partition key + 向量 JSON
            PreparedStatement vps = conn.prepareStatement(
                    "INSERT INTO vec_chunks (rowid, user_id, kb_id, chunk_embedding) VALUES (?,?,?,?)");
            vps.setInt(1, rowId);
            vps.setString(2, userId);
            vps.setString(3, kbId);
            vps.setString(4, vecJson);
            vps.executeUpdate();
            vps.close();
            return rowId;
        } catch (Exception e) {
            throw new RuntimeException("插入切片失败: " + e.getMessage(), e);
        }
    }

    // 按行号批量删切片（上传失败回滚）：正文表按 id、向量表按 rowid，两个表都要删
    @Override
    public void deleteChunks(String userId, List<Integer> rowIds) {
        if (rowIds == null || rowIds.isEmpty()) {
            return;                                     // 没入库过任何块 → 无事可回滚
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(rowIds.size(), "?"));
        try {
            PreparedStatement vps = conn.prepareStatement(
                    "DELETE FROM vec_chunks WHERE rowid IN (" + placeholders + ")");
            for (int i = 0; i < rowIds.size(); i++) {
                vps.setInt(i + 1, rowIds.get(i));
            }
            vps.executeUpdate();
            vps.close();

            PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM chunks WHERE id IN (" + placeholders + ")");
            for (int i = 0; i < rowIds.size(); i++) {
                ps.setInt(i + 1, rowIds.get(i));
            }
            ps.executeUpdate();
            ps.close();
        } catch (Exception e) {
            throw new RuntimeException("回滚删除切片失败: " + e.getMessage(), e);
        }
    }

    // KNN 检索：MATCH 查询 + user_id/kb_id 过滤（partition key 自动只扫本区）
    // 注意：vec0 要求 KNN 查询必须带 k 约束（不能用参数化 LIMIT，坑：静态解析失败报
    // "A LIMIT or 'k = ?' constraint is required on vec0 knn queries"）
    // 去重（10-10 修复）：同一文档重复上传产生内容/向量完全相同的重复块，KNN 前 topK
    // 可能全是同一内容 → 多取几倍候选（fetchK 上限 100），按内容去重保留最低距离，
    // 再截断回 topK（KNN 按距离升序返回，首次见到的内容即最低距离那条）
    @Override
    public List<SearchHit> search(String userId, String kbId, float[] queryEmbedding, int topK, double threshold) {
        int fetchK = Math.min(Math.max(topK * 3, topK), 100);
        List<SearchHit> hits = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            String sql = "SELECT c.content, v.distance FROM vec_chunks v " +
                    "JOIN chunks c ON c.id = v.rowid " +
                    "WHERE v.chunk_embedding MATCH ? AND v.user_id = ? AND v.kb_id = ? " +
                    "AND k = ?";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, toJson(queryEmbedding));
            ps.setString(2, userId);
            ps.setString(3, kbId);
            ps.setInt(4, fetchK);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                double score = rs.getDouble("distance");
                String content = rs.getString("content");
                if (score <= threshold && seen.add(content)) {   // 阈值过滤 + 内容去重
                    hits.add(new SearchHit(content, score));
                }
            }
            rs.close();
            ps.close();
        } catch (Exception e) {
            throw new RuntimeException("检索失败: " + e.getMessage(), e);
        }
        // 截断回 topK；去重后不足 topK 属正常（库里唯一内容就那么多）
        return hits.size() > topK ? hits.subList(0, topK) : hits;
    }

    @Override
    public int deleteByKb(String userId, String kbId) {
        try {
            // 先按 kb_id 找出该库的 rowid 列表，再删虚拟表（vec0 支持 DELETE WHERE）
            PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM vec_chunks WHERE user_id = ? AND kb_id = ?");
            ps.setString(1, userId);
            ps.setString(2, kbId);
            int vecDeleted = ps.executeUpdate();
            ps.close();
            PreparedStatement ps2 = conn.prepareStatement(
                    "DELETE FROM chunks WHERE user_id = ? AND kb_id = ?");
            ps2.setString(1, userId);
            ps2.setString(2, kbId);
            int deleted = ps2.executeUpdate();
            ps2.close();
            return Math.max(vecDeleted, deleted);
        } catch (Exception e) {
            throw new RuntimeException("删除知识库失败: " + e.getMessage(), e);
        }
    }

    // 归属校验（R10 显式版）：knowledge_base 表里有 (kb_id, user_id) 行才算拥有
    @Override
    public boolean ownsKb(String userId, String kbId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT EXISTS(SELECT 1 FROM knowledge_base WHERE kb_id = ? AND user_id = ?)")) {
            ps.setString(1, kbId);
            ps.setString(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.getBoolean(1);
            }
        } catch (Exception e) {
            throw new RuntimeException("校验知识库归属失败: " + e.getMessage(), e);
        }
    }

    // ===== KnowledgeBaseRepository 实现（W8 09-29）=====

    // 新用户自动建默认库：INSERT OR IGNORE 幂等（已存在不报错），默认库 kb_id = userId + "-default"
    @Override
    public KnowledgeBase ensureUserWithDefaultKb(String userId) {
        try {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR IGNORE INTO users (user_id) VALUES (?)")) {
                ps.setString(1, userId);
                ps.executeUpdate();
            }
            String defaultKbId = userId + "-default";
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR IGNORE INTO knowledge_base (kb_id, user_id, name) VALUES (?,?,?)")) {
                ps.setString(1, defaultKbId);
                ps.setString(2, userId);
                ps.setString(3, "默认知识库");
                ps.executeUpdate();
            }
            return new KnowledgeBase(defaultKbId, userId, "默认知识库");
        } catch (Exception e) {
            throw new RuntimeException("创建默认知识库失败: " + e.getMessage(), e);
        }
    }

    // 新建命名知识库：kb_id 用 UUID 短前缀（跨用户不撞名）
    @Override
    public KnowledgeBase createKb(String userId, String name) {
        try {
            String kbId = "kb-" + UUID.randomUUID().toString().substring(0, 8);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO knowledge_base (kb_id, user_id, name) VALUES (?,?,?)")) {
                ps.setString(1, kbId);
                ps.setString(2, userId);
                ps.setString(3, name);
                ps.executeUpdate();
            }
            return new KnowledgeBase(kbId, userId, name);
        } catch (Exception e) {
            throw new RuntimeException("创建知识库失败: " + e.getMessage(), e);
        }
    }

    // 列出用户全部知识库（含默认库）
    @Override
    public List<KnowledgeBase> listByUser(String userId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT kb_id, user_id, name FROM knowledge_base WHERE user_id = ? ORDER BY created_at")) {
            ps.setString(1, userId);
            List<KnowledgeBase> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new KnowledgeBase(
                            rs.getString("kb_id"), rs.getString("user_id"), rs.getString("name")));
                }
            }
            return list;
        } catch (Exception e) {
            throw new RuntimeException("列出知识库失败: " + e.getMessage(), e);
        }
    }

    // 列出全部用户的知识库（管理端全库视角，不做归属过滤）
    @Override
    public List<KnowledgeBase> listAll() {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT kb_id, user_id, name FROM knowledge_base ORDER BY user_id, created_at")) {
            List<KnowledgeBase> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new KnowledgeBase(
                            rs.getString("kb_id"), rs.getString("user_id"), rs.getString("name")));
                }
            }
            return list;
        } catch (Exception e) {
            throw new RuntimeException("列出全部知识库失败: " + e.getMessage(), e);
        }
    }

    // 删库：先删库内全部切片，再删 knowledge_base 行（调用方已做归属校验）
    @Override
    public void deleteKb(String userId, String kbId) {
        try {
            deleteByKb(userId, kbId);
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM knowledge_base WHERE kb_id = ? AND user_id = ?")) {
                ps.setString(1, kbId);
                ps.setString(2, userId);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            throw new RuntimeException("删除知识库失败: " + e.getMessage(), e);
        }
    }

    // 账号合并：from 的全部知识库并入 to——
    // 默认库内容并入 to 默认库（kb_id 冲突）；非默认库改归属保留 kb_id（全局唯一不撞名）
    // vec_chunks 是虚拟表分区列不可 UPDATE → 逐行 DELETE+INSERT（rowid 不变，embedding 从 chunks 表取回）
    @Override
    public void transferTo(String fromUserId, String toUserId) {
        try {
            String fromDefault = fromUserId + "-default";
            String toDefault = toUserId + "-default";
            ensureUserWithDefaultKb(toUserId);   // 合并目标必须有默认库

            // 1) 读 from 的全部行（id + 原 kb_id + embedding JSON，用于重建 vec 行）
            List<Long> rowIds = new ArrayList<>();
            List<String> newKbIds = new ArrayList<>();
            List<String> vecJsons = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, kb_id, embedding FROM chunks WHERE user_id = ?")) {
                ps.setString(1, fromUserId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String oldKb = rs.getString("kb_id");
                        rowIds.add(rs.getLong("id"));
                        newKbIds.add(oldKb.equals(fromDefault) ? toDefault : oldKb);
                        vecJsons.add(rs.getString("embedding"));
                    }
                }
            }

            // 2) chunks 普通表直接 UPDATE：默认库内容并入 to 默认库，非默认库只改归属
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE chunks SET user_id = ?, kb_id = ? WHERE user_id = ? AND kb_id = ?")) {
                ps.setString(1, toUserId);
                ps.setString(2, toDefault);
                ps.setString(3, fromUserId);
                ps.setString(4, fromDefault);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE chunks SET user_id = ? WHERE user_id = ? AND kb_id <> ?")) {
                ps.setString(1, toUserId);
                ps.setString(2, fromUserId);
                ps.setString(3, fromDefault);
                ps.executeUpdate();
            }

            // 3) vec_chunks 虚拟表：分区列不可 UPDATE → 逐行 DELETE + INSERT（rowid 不变）
            for (int i = 0; i < rowIds.size(); i++) {
                try (PreparedStatement del = conn.prepareStatement("DELETE FROM vec_chunks WHERE rowid = ?")) {
                    del.setLong(1, rowIds.get(i));
                    del.executeUpdate();
                }
                try (PreparedStatement ins = conn.prepareStatement(
                        "INSERT INTO vec_chunks (rowid, user_id, kb_id, chunk_embedding) VALUES (?,?,?,?)")) {
                    ins.setLong(1, rowIds.get(i));
                    ins.setString(2, toUserId);
                    ins.setString(3, newKbIds.get(i));
                    ins.setString(4, vecJsons.get(i));
                    ins.executeUpdate();
                }
            }

            // 4) knowledge_base：from 默认库行删（内容已并入 to 默认库），非默认库行改归属
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM knowledge_base WHERE user_id = ? AND kb_id = ?")) {
                ps.setString(1, fromUserId);
                ps.setString(2, fromDefault);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE knowledge_base SET user_id = ? WHERE user_id = ? AND kb_id <> ?")) {
                ps.setString(1, toUserId);
                ps.setString(2, fromUserId);
                ps.setString(3, fromDefault);
                ps.executeUpdate();
            }
            // 5) users：from 身份清理（账号已并入）
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM users WHERE user_id = ?")) {
                ps.setString(1, fromUserId);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            throw new RuntimeException("知识库迁移失败: " + e.getMessage(), e);
        }
    }

    // float[] → "[0.1, 0.2, ...]"（vec0 接受 JSON 数组字符串）
    private String toJson(float[] arr) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(arr[i]);
        }
        return sb.append("]").toString();
    }

    public void close() throws Exception {
        if (conn != null) conn.close();
    }
}