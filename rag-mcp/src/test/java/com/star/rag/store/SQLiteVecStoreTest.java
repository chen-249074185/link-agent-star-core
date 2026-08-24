package com.star.rag.store;

import com.star.rag.security.KnowledgeBaseGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

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

    // topK 参数化验证：返回条数正确 + 最相关永远排第一 + 整条距离非降序
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 5})
    void search_respectsTopK(int topK) {
        // 插入 5 条："片段i" 对应"第 i 维方向"
        for (int i = 0; i < 5; i++) {
            store.insertChunk("u1", "kb1", "片段" + i, axis(i), "{}");
        }

        // 查询 = 第 0 维方向 → "片段0" 距离 0 最相关
        // 阈值 1.5：两两正交距离都是 √2≈1.414 < 1.5 → 5 条全在范围内，条数只由 topK 决定
        List<VectorStore.SearchHit> hits = store.search("u1", "kb1", axis(0), topK, 1.5);

        // 断言1：返回条数 = topK（验证 topK 生效）
        assertEquals(topK, hits.size());

        // 断言2：最相关的永远排第一（vec0 KNN 按距离升序返回）
        assertEquals("片段0", hits.get(0).content());

        // 断言3：整条结果距离非降序（相邻两条 后 >= 前）
        for (int i = 1; i < hits.size(); i++) {
            assertTrue(hits.get(i).score() >= hits.get(i - 1).score());
        }
    }

    // 阈值参数化验证（紧阈值）：距离 > threshold 的远端向量被过滤
    // 插入 axis(0)=距离0 + axis(1)=距离√2≈1.414，阈值 0.5/1.0 都小于 1.414 → 只剩最近那条
    @ParameterizedTest
    @ValueSource(doubles = {0.5, 1.0})
    void search_tightThresholdFiltersFar(double threshold) {
        store.insertChunk("u1", "kb1", "最近", axis(0), "{}");
        store.insertChunk("u1", "kb1", "正交远", axis(1), "{}");

        List<VectorStore.SearchHit> hits = store.search("u1", "kb1", axis(0), 5, threshold);
        assertEquals(1, hits.size());
        assertEquals("最近", hits.get(0).content());
    }

    // 阈值参数化验证（宽松阈值）：阈值 1.5/2.0 ≥ 1.414 → 两条都放行
    @ParameterizedTest
    @ValueSource(doubles = {1.5, 2.0})
    void search_looseThresholdAllowsNear(double threshold) {
        store.insertChunk("u1", "kb1", "最近", axis(0), "{}");
        store.insertChunk("u1", "kb1", "正交远", axis(1), "{}");

        List<VectorStore.SearchHit> hits = store.search("u1", "kb1", axis(0), 5, threshold);
        assertEquals(2, hits.size());
    }

    // 性能基准：1000 条随机向量（1024 维）入库后，KNN 查询耗时 < 100ms 达标
    @Test
    void performance_1000VectorsQueryUnder100ms() {
        Random rand = new Random(42);   // 固定 seed：每次跑同一条随机数据，结果可复现
        List<float[]> vecs = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            float[] v = randomVec(rand);
            vecs.add(v);
            store.insertChunk("u1", "kb1", "基准片段" + i, v, "{}");
        }

        // 预热一次：JIT 编译 + 连接/页缓存就绪，不算进基准（第一次查询通常偏慢）
        store.search("u1", "kb1", vecs.get(0), 5, 1.5);

        long maxMs = 0;
        for (int i = 0; i < 10; i++) {
            // 用库里真实存在的向量做查询 → 自身距离 0，必然命中，测的是"带命中的真实检索"
            float[] query = vecs.get((i * 97) % 1000);
            long start = System.nanoTime();
            List<VectorStore.SearchHit> hits = store.search("u1", "kb1", query, 5, 1.5);
            long ms = (System.nanoTime() - start) / 1_000_000;
            maxMs = Math.max(maxMs, ms);
            assertTrue(hits.size() > 0, "查询自身向量应命中");   // 顺带校验检索正确性
        }

        assertTrue(maxMs < 100, "1000 条向量 KNN 查询耗时 " + maxMs + "ms，超过 100ms 上限");
    }

    // 造随机向量：每维在 [-1,1)（固定 seed 保证可复现）
    private float[] randomVec(Random rand) {
        float[] v = new float[1024];
        for (int i = 0; i < v.length; i++) {
            v[i] = rand.nextFloat() * 2 - 1;
        }
        return v;
    }

    // ===== R10 归属校验（ownsKb + KnowledgeBaseGuard）=====

    // 空库/无别人占用 → 归该用户
    @Test
    void ownsKb_trueWhenNoOtherUserClaims() {
        store.insertChunk("u1", "kb1", "自己的", axis(0), "{}");
        assertTrue(store.ownsKb("u1", "kb1"));      // 自己上传过 → 有
        assertTrue(store.ownsKb("u2", "fresh-kb")); // 全新库名没人占用 → 也归 u2（可认领）
    }

    // 别的用户已占用同名库 → 不归我（R10 越权核心）
    @Test
    void ownsKb_falseWhenOtherUserClaims() {
        store.insertChunk("u1", "kb1", "别人的库", axis(0), "{}");
        assertTrue(!store.ownsKb("u2", "kb1"));     // u2 想碰 u1 的 kb1 → 拒绝
    }

    // Guard 校验层：归属通过返回 null；越权返回话术 + 审计日志（走 stderr）
    @Test
    void guard_allowsOwner_deniesIntruder() {
        KnowledgeBaseGuard guard = new KnowledgeBaseGuard(store);
        store.insertChunk("u1", "kb1", "只有u1的内容", axis(0), "{}");

        assertTrue(guard.checkAccess("u1", "kb1") == null);          // 主人 → 放行
        assertTrue(guard.checkAccess("u2", "kb1") != null);          // 入侵者 → 拒绝（含审计日志）
        assertTrue(guard.checkAccess("u2", "kb1").contains("无权"));
    }
}