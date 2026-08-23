package com.star.rag.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// SQLiteVecStore 集成测试：真实 SQLite 文件 + vec0.dll（验证 D37 方案 A 全链路）
class SQLiteVecStoreTest {

    // 指向 spike 里已下载的 vec0.dll
    private static final String VEC_DLL = "D:/星链智核/agent/spike/sqlite-vec/vec0.dll";

    private SQLiteVecStore store;

    @BeforeEach
    void setUp() throws Exception {
        store = new SQLiteVecStore(":memory:", VEC_DLL);
    }

    @AfterEach
    void tearDown() throws Exception {
        store.close();
    }

    // 造一个可预测的"方向向量"：第 i 维 = 1，其余 0（1024 维）
    private float[] axis(int i) {
        float[] v = new float[1024];
        v[i] = 1.0f;
        return v;
    }

    @Test
    void insertAndSearch_findsClosest() {
        store.insertChunk("u1", "kb1", "北京的秋天很美", axis(0), "{}");
        store.insertChunk("u1", "kb1", "上海的夏天很热", axis(1), "{}");
        store.insertChunk("u1", "kb1", "杭州的春天多雨", axis(2), "{}");

        // 查询向量 = 第 0 维方向 → 应命中"北京"那条（距离最小）
        List<VectorStore.SearchHit> hits = store.search("u1", "kb1", axis(0), 3, 1.5);
        assertEquals(3, hits.size());
        assertEquals("北京的秋天很美", hits.get(0).content());
    }

    // 私有库隔离：u1 的库搜不到 u2 的内容
    @Test
    void search_isolatedByUser() {
        store.insertChunk("u1", "kb1", "只有用户1能看到的内容", axis(0), "{}");

        List<VectorStore.SearchHit> hits = store.search("u2", "kb1", axis(0), 5, 1.5);
        assertEquals(0, hits.size());
    }

    @Test
    void deleteByKb_removesAll() {
        store.insertChunk("u1", "kb1", "甲", axis(0), "{}");
        store.insertChunk("u1", "kb1", "乙", axis(1), "{}");

        int deleted = store.deleteByKb("u1", "kb1");
        assertTrue(deleted >= 2);

        assertEquals(0, store.search("u1", "kb1", axis(0), 5, 1.5).size());
    }

    // 阈值过滤：距离超过 threshold 的不返回（vec0 默认 L2 距离，正交向量距离=√2≈1.414）
    @Test
    void search_respectsThreshold() {
        store.insertChunk("u1", "kb1", "正交向量", axis(0), "{}");

        // 查询 = 第 1 维（与 0 维正交，L2 距离 √2）→ 阈值 1.0 下应被过滤
        List<VectorStore.SearchHit> hits = store.search("u1", "kb1", axis(1), 5, 1.0);
        assertEquals(0, hits.size());
    }
}