package com.star.rag.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

// VectorStore 的 sqlite-vec 实现（D37 方案 A）
// 双表：vec_chunks 虚拟表（向量 KNN）+ chunks 普通表（正文/元数据）
public class SQLiteVecStore implements VectorStore {

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
    @Override
    public List<SearchHit> search(String userId, String kbId, float[] queryEmbedding, int topK, double threshold) {
        List<SearchHit> hits = new ArrayList<>();
        try {
            String sql = "SELECT c.content, v.distance FROM vec_chunks v " +
                    "JOIN chunks c ON c.id = v.rowid " +
                    "WHERE v.chunk_embedding MATCH ? AND v.user_id = ? AND v.kb_id = ? " +
                    "AND k = ?";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, toJson(queryEmbedding));
            ps.setString(2, userId);
            ps.setString(3, kbId);
            ps.setInt(4, topK);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                double score = rs.getDouble("distance");
                if (score <= threshold) {   // 距离越小越相关（阈值 = 最大可接受距离）
                    hits.add(new SearchHit(rs.getString("content"), score));
                }
            }
            rs.close();
            ps.close();
        } catch (Exception e) {
            throw new RuntimeException("检索失败: " + e.getMessage(), e);
        }
        return hits;
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

    // 归属校验（R10）：kb_id 由第一个上传内容的用户"认领"——只要存在"其他用户"的同名库，就不归我
    @Override
    public boolean ownsKb(String userId, String kbId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT EXISTS(SELECT 1 FROM chunks WHERE kb_id = ? AND user_id <> ?)")) {
            ps.setString(1, kbId);
            ps.setString(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return !rs.getBoolean(1);   // 没有"别人"占用 → 归我
            }
        } catch (Exception e) {
            throw new RuntimeException("校验知识库归属失败: " + e.getMessage(), e);
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