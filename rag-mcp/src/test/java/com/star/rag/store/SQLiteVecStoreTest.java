package com.star.rag.store;

import com.star.rag.security.KnowledgeBaseGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

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
        store.insertChunk("u1", "kb1", "北京的秋天很美", "测试.txt", axis(0), "{}");
        store.insertChunk("u1", "kb1", "上海的夏天很热", "测试.txt", axis(1), "{}");
        store.insertChunk("u1", "kb1", "杭州的春天多雨", "测试.txt", axis(2), "{}");

        // 查询向量 = 第 0 维方向 → 应命中"北京"那条（距离最小）
        List<VectorStore.SearchHit> hits = store.search("u1", "kb1", axis(0), 3, 1.5);
        assertEquals(3, hits.size());
        assertEquals("北京的秋天很美", hits.get(0).content());
    }

    // 私有库隔离：u1 的库搜不到 u2 的内容
    @Test
    void search_isolatedByUser() {
        store.insertChunk("u1", "kb1", "只有用户1能看到的内容", "测试.txt", axis(0), "{}");

        List<VectorStore.SearchHit> hits = store.search("u2", "kb1", axis(0), 5, 1.5);
        assertEquals(0, hits.size());
    }

    @Test
    void deleteByKb_removesAll() {
        store.insertChunk("u1", "kb1", "甲", "测试.txt", axis(0), "{}");
        store.insertChunk("u1", "kb1", "乙", "测试.txt", axis(1), "{}");

        int deleted = store.deleteByKb("u1", "kb1");
        assertTrue(deleted >= 2);

        assertEquals(0, store.search("u1", "kb1", axis(0), 5, 1.5).size());
    }

    // 阈值过滤：距离超过 threshold 的不返回（vec0 默认 L2 距离，正交向量距离=√2≈1.414）
    @Test
    void search_respectsThreshold() {
        store.insertChunk("u1", "kb1", "正交向量", "测试.txt", axis(0), "{}");

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
            store.insertChunk("u1", "kb1", "片段" + i, "测试.txt", axis(i), "{}");
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
        store.insertChunk("u1", "kb1", "最近", "测试.txt", axis(0), "{}");
        store.insertChunk("u1", "kb1", "正交远", "测试.txt", axis(1), "{}");

        List<VectorStore.SearchHit> hits = store.search("u1", "kb1", axis(0), 5, threshold);
        assertEquals(1, hits.size());
        assertEquals("最近", hits.get(0).content());
    }

    // 阈值参数化验证（宽松阈值）：阈值 1.5/2.0 ≥ 1.414 → 两条都放行
    @ParameterizedTest
    @ValueSource(doubles = {1.5, 2.0})
    void search_looseThresholdAllowsNear(double threshold) {
        store.insertChunk("u1", "kb1", "最近", "测试.txt", axis(0), "{}");
        store.insertChunk("u1", "kb1", "正交远", "测试.txt", axis(1), "{}");

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
            store.insertChunk("u1", "kb1", "基准片段" + i, "测试.txt", v, "{}");
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

    // ===== R10 归属校验（显式版：knowledge_base 表）=====

    // 显式归属：默认库归该用户，别人/未知库不拥有
    @Test
    void ownsKb_explicitOwnership() {
        store.ensureUserWithDefaultKb("u1");               // 新用户自动建默认库 u1-default
        assertTrue(store.ownsKb("u1", "u1-default"));      // 自己默认库 → 拥有
        assertTrue(!store.ownsKb("u2", "u1-default"));     // u2 想碰 u1 的库 → 拒绝
        assertTrue(!store.ownsKb("u1", "kb-unknown"));     // 不存在的库也不拥有
    }

    // Guard 校验层：归属通过返回 null；越权返回话术 + 审计日志（走 stderr）
    @Test
    void guard_allowsOwner_deniesIntruder() {
        KnowledgeBaseGuard guard = new KnowledgeBaseGuard(store);
        store.ensureUserWithDefaultKb("u1");
        store.insertChunk("u1", "u1-default", "只有u1的内容", "测试.txt", axis(0), "{}");

        assertTrue(guard.checkAccess("u1", "u1-default") == null);   // 主人 → 放行
        assertTrue(guard.checkAccess("u2", "u1-default") != null);   // 入侵者 → 拒绝（含审计日志）
        assertTrue(guard.checkAccess("u2", "u1-default").contains("无权"));
    }

    // deleteChunks 按行号删：只删指定的，别的保留（上传回滚真实路径）
    @Test
    void deleteChunks_removesOnlySpecified() {
        int r1 = store.insertChunk("u1", "kb1", "甲", "测试.txt", axis(0), "{}");
        int r2 = store.insertChunk("u1", "kb1", "乙", "测试.txt", axis(1), "{}");
        int r3 = store.insertChunk("u1", "kb1", "丙", "测试.txt", axis(2), "{}");

        store.deleteChunks("u1", List.of(r1, r3));   // 删甲和丙，留乙

        List<VectorStore.SearchHit> hits = store.search("u1", "kb1", axis(1), 5, 1.5);
        assertEquals(1, hits.size());
        assertEquals("乙", hits.get(0).content());
    }

    // 去重回归（10-10 M3 发现的 topK 重复块 bug）：同一文档重复上传（内容+向量全同）后，
    // 检索结果按内容去重只保留一条；重复块不再霸占 topK 名额
    @Test
    void search_dedupesDuplicateContent() {
        store.insertChunk("u1", "kb1", "北京海淀两居室6500元", "测试.txt", axis(0), "{}");
        store.insertChunk("u1", "kb1", "北京海淀两居室6500元", "测试.txt", axis(0), "{}");   // 重复上传第 2 次
        store.insertChunk("u1", "kb1", "北京海淀两居室6500元", "测试.txt", axis(0), "{}");   // 重复上传第 3 次
        store.insertChunk("u1", "kb1", "上海单间3800元", "测试.txt", axis(1), "{}");

        List<VectorStore.SearchHit> hits = store.search("u1", "kb1", axis(0), 5, 1.5);

        assertEquals(2, hits.size(), "重复内容应去重为一条");
        assertEquals("北京海淀两居室6500元", hits.get(0).content(), "最近那条保留");
        assertEquals("上海单间3800元", hits.get(1).content());
    }

    // 去重后仍按 topK 截断：唯一内容多于 topK 时只回 topK 条，且无重复
    @Test
    void search_dedupThenTopKTruncation() {
        for (int i = 0; i < 5; i++) {
            store.insertChunk("u1", "kb1", "重复块A", "测试.txt", axis(0), "{}");   // 5 份相同内容
            store.insertChunk("u1", "kb1", "片段" + i, "测试.txt", axis(i), "{}");
        }

        List<VectorStore.SearchHit> hits = store.search("u1", "kb1", axis(0), 3, 1.5);

        assertEquals(3, hits.size(), "去重后仍按 topK 截断");
        Set<String> contents = new HashSet<>();
        for (VectorStore.SearchHit h : hits) {
            assertTrue(contents.add(h.content()), "结果不应有重复内容: " + h.content());
        }
        long dupCount = hits.stream().filter(h -> h.content().equals("重复块A")).count();
        assertEquals(1, dupCount, "重复块A 只出现一次");
    }

    // 分文件列表：同名文件多块聚合一条（块数+最后时间），不同文件分开；老数据无文件名归"(未知文件)"
    @Test
    void listFiles_groupsByFilename() {
        store.insertChunk("u1", "kb1", "甲一", "a.txt", axis(0), "{}");
        store.insertChunk("u1", "kb1", "甲二", "a.txt", axis(1), "{}");
        store.insertChunk("u1", "kb1", "乙一", "b.md", axis(2), "{}");
        store.insertChunk("u1", "kb2", "别的库", "c.txt", axis(3), "{}");

        List<VectorStore.FileInfo> files = store.listFiles("u1", "kb1");

        assertEquals(2, files.size());
        assertEquals("a.txt", files.get(0).filename());
        assertEquals(2, files.get(0).chunkCount());
        assertEquals("b.md", files.get(1).filename());
        assertEquals(1, files.get(1).chunkCount());
    }

    // 分文件删除：只删指定文件的所有块，其他文件不动；vec/chunks 双删后检索不到
    @Test
    void deleteFile_removesOnlyThatFile() {
        store.insertChunk("u1", "kb1", "甲一", "a.txt", axis(0), "{}");
        store.insertChunk("u1", "kb1", "甲二", "a.txt", axis(1), "{}");
        store.insertChunk("u1", "kb1", "乙一", "b.md", axis(2), "{}");

        int deleted = store.deleteFile("u1", "kb1", "a.txt");

        assertEquals(2, deleted, "同名文件两块全删");
        assertEquals(1, store.listFiles("u1", "kb1").size(), "只剩 b.md");
        List<VectorStore.SearchHit> hits = store.search("u1", "kb1", axis(2), 5, 1.5);
        assertEquals(1, hits.size(), "b.md 的内容仍可检索");
        assertEquals("乙一", hits.get(0).content());
        // 删除不存在的文件：返回 0 不报错
        assertEquals(0, store.deleteFile("u1", "kb1", "不存在.txt"));
    }

    // 分文件删除的隔离：u2 删不到 u1 库的文件（调用方 guard 负责拒绝，底层也按用户隔离）
    @Test
    void deleteFile_isolatedByUser() {
        store.insertChunk("u1", "kb1", "甲的", "a.txt", axis(0), "{}");

        int deleted = store.deleteFile("u2", "kb1", "a.txt");

        assertEquals(0, deleted, "u2 删不到 u1 的文件");
        assertEquals(1, store.listFiles("u1", "kb1").size());
    }

    // ===== 共享房源库（11-03 方案1）：通用公开数据全用户只读可检索 =====

    // 共享库内容存 system 名下，任何用户都能检索到（resolveOwner 自动换身份）
    @Test
    void search_sharedKb_visibleToAllUsers() {
        store.insertChunk(SQLiteVecStore.SHARED_OWNER, SQLiteVecStore.SHARED_KB_ID,
                "北京海淀学院路两居室月租6500元", "北京.txt", axis(0), "{}");

        // u1/u2 都能命中共享库
        List<VectorStore.SearchHit> hits = store.search("u1", SQLiteVecStore.SHARED_KB_ID, axis(0), 5, 1.5);
        assertEquals(1, hits.size());
        assertEquals("北京海淀学院路两居室月租6500元", hits.get(0).content());
        List<VectorStore.SearchHit> hits2 = store.search("u2", SQLiteVecStore.SHARED_KB_ID, axis(0), 5, 1.5);
        assertEquals(1, hits2.size());
    }

    // 共享库文件列表：任意用户可看（resolveOwner 生效）
    @Test
    void listFiles_sharedKb_visibleToAllUsers() {
        store.insertChunk(SQLiteVecStore.SHARED_OWNER, SQLiteVecStore.SHARED_KB_ID, "内容", "北京.txt", axis(0), "{}");

        List<VectorStore.FileInfo> files = store.listFiles("u1", SQLiteVecStore.SHARED_KB_ID);
        assertEquals(1, files.size());
        assertEquals("北京.txt", files.get(0).filename());
    }

    // 共享库删除：任意 userId 传共享库都按 system 身份定位（谁"看起来"在删都是 system 的数据——
// 普通用户的写拦截在工具层 RagMcpServer handler，store 层只管身份解析）
    @Test
    void deleteFile_sharedKb_resolvesToSystemOwner() {
        store.insertChunk(SQLiteVecStore.SHARED_OWNER, SQLiteVecStore.SHARED_KB_ID, "内容", "北京.txt", axis(0), "{}");

        assertEquals(1, store.deleteFile("u1", SQLiteVecStore.SHARED_KB_ID, "北京.txt"),
                "共享库按 system 身份定位删除（写权限由工具层拦普通用户）");
        assertEquals(0, store.search("u1", SQLiteVecStore.SHARED_KB_ID, axis(0), 5, 1.5).size());
    }

    // Guard：共享库人人可读（不查归属）；私有库照旧越权拒绝
    @Test
    void guard_sharedKb_allowsEveryone() {
        KnowledgeBaseGuard guard = new KnowledgeBaseGuard(store);
        assertEquals(null, guard.checkAccess("u1", SQLiteVecStore.SHARED_KB_ID), "共享库放行");
        assertEquals(null, guard.checkAccess("u2", SQLiteVecStore.SHARED_KB_ID), "共享库放行（任意用户）");
        store.ensureUserWithDefaultKb("u1");
        assertEquals(null, guard.checkAccess("u1", "u1-default"), "私有库主人放行");
        assertTrue(guard.checkAccess("u2", "u1-default") != null, "私有库越权拒绝");
    }

    // 共享库归属行存在（system 名下，管理端可见）
    @Test
    void knowledgeBase_sharedRowExists() {
        List<KnowledgeBaseRepository.KnowledgeBase> all = store.listAll();
        boolean found = all.stream().anyMatch(kb ->
                kb.kbId().equals(SQLiteVecStore.SHARED_KB_ID)
                        && kb.userId().equals(SQLiteVecStore.SHARED_OWNER));
        assertTrue(found, "共享房源库应在全库清单中（system 名下）");
    }

    // 账号合并：from 默认库内容并入 to 默认库、非默认库改归属、越权隔离仍生效、from 身份清理
    @Test
    void transferTo_mergesKbsIntoToUser() {
        store.ensureUserWithDefaultKb("u1");
        store.ensureUserWithDefaultKb("u2");
        store.insertChunk("u2", "u2-default", "B默认库内容", "测试.txt", axis(0), "{}");
        String customKb = store.createKb("u2", "自定义库").kbId();
        store.insertChunk("u2", customKb, "B自定义库内容", "测试.txt", axis(1), "{}");

        store.transferTo("u2", "u1");

        // 默认库内容并入 u1-default，u1 能搜到
        List<VectorStore.SearchHit> hits = store.search("u1", "u1-default", axis(0), 5, 1.5);
        assertEquals(1, hits.size(), "u1 默认库应含迁移来的内容");
        assertEquals("B默认库内容", hits.get(0).content());
        // 非默认库改归属保留 kb_id，u1 能搜到
        List<VectorStore.SearchHit> custom = store.search("u1", customKb, axis(1), 5, 1.5);
        assertEquals(1, custom.size(), "非默认库改归属后可被 u1 检索");
        // u2 已无知识库（身份清理）
        assertEquals(0, store.listByUser("u2").size(), "u2 知识库应清空");
        // 越权隔离仍生效：u2 搜 u1 的库被拒
        String denied = new KnowledgeBaseGuard(store).checkAccess("u2", "u1-default");
        org.junit.jupiter.api.Assertions.assertNotNull(denied, "迁移后 u2 仍无权访问 u1 的库");
    }
}