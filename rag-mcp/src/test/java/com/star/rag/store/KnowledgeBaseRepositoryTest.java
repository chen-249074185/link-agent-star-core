package com.star.rag.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// KnowledgeBaseRepository 集成测试：自动默认库/建库/列表/删库/显式归属隔离
class KnowledgeBaseRepositoryTest {

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

    // 新用户自动建默认库：ensureUserWithDefaultKb 幂等，返回默认库且列表可见
    @Test
    void ensureUserWithDefaultKb_createsDefaultOnce() {
        KnowledgeBaseRepository.KnowledgeBase kb = store.ensureUserWithDefaultKb("u1");
        assertEquals("u1-default", kb.kbId());
        assertEquals("默认知识库", kb.name());

        store.ensureUserWithDefaultKb("u1");          // 再调一次不重复建
        assertEquals(1, store.listByUser("u1").size());
        assertTrue(store.ownsKb("u1", "u1-default"));
    }

    // 建命名库 + 列表：默认库 + 新库都在
    @Test
    void createKb_andListByUser() {
        store.ensureUserWithDefaultKb("u1");
        KnowledgeBaseRepository.KnowledgeBase work = store.createKb("u1", "工作资料");

        List<KnowledgeBaseRepository.KnowledgeBase> kbs = store.listByUser("u1");
        assertEquals(2, kbs.size());
        assertTrue(kbs.stream().anyMatch(k -> k.kbId().equals(work.kbId())));
        assertTrue(kbs.stream().anyMatch(k -> k.name().equals("默认知识库")));
    }

    // 不同用户列表互不可见（R10 隔离）
    @Test
    void listByUser_isolatedBetweenUsers() {
        store.ensureUserWithDefaultKb("u1");
        store.ensureUserWithDefaultKb("u2");

        List<KnowledgeBaseRepository.KnowledgeBase> u1 = store.listByUser("u1");
        assertTrue(u1.stream().noneMatch(k -> k.userId().equals("u2")));
        assertEquals(1, u1.size());
    }

    // 删库：knowledge_base 行 + 库内全部切片一起删，别的库不受影响
    @Test
    void deleteKb_removesChunksAndRow() {
        store.ensureUserWithDefaultKb("u1");
        KnowledgeBaseRepository.KnowledgeBase work = store.createKb("u1", "工作资料");
        store.insertChunk("u1", work.kbId(), "工作内容", "测试.txt", axis(0), "{}");

        store.deleteKb("u1", work.kbId());

        assertTrue(!store.ownsKb("u1", work.kbId()));          // 归属行没了
        assertTrue(store.search("u1", work.kbId(), axis(0), 5, 1.5).isEmpty());  // 切片没了
        assertTrue(store.ownsKb("u1", "u1-default"));          // 默认库还在
    }

    // 管理端全库视角：listAll 看到所有用户的所有库（不做归属过滤）
    @Test
    void listAll_seesEveryUsersKbs() {
        store.ensureUserWithDefaultKb("u1");
        store.ensureUserWithDefaultKb("u2");
        KnowledgeBaseRepository.KnowledgeBase work = store.createKb("u2", "工作资料");

        List<KnowledgeBaseRepository.KnowledgeBase> all = store.listAll();

        assertEquals(3, all.size());   // u1 默认 + u2 默认 + u2 工作资料
        assertTrue(all.stream().anyMatch(k -> k.kbId().equals("u1-default")));
        assertTrue(all.stream().anyMatch(k -> k.kbId().equals(work.kbId()) && k.userId().equals("u2")));
    }

    // 管理端删除任意用户的知识库：deleteKb 本身不过归属校验（guard 在 server 层），删他人库可行
    @Test
    void adminDelete_removesAnotherUsersKb() {
        store.ensureUserWithDefaultKb("u1");
        store.ensureUserWithDefaultKb("u2");
        store.insertChunk("u2", "u2-default", "u2 的私有内容", "测试.txt", axis(0), "{}");

        store.deleteKb("u2", "u2-default");   // 管理员视角：直接删 u2 的库

        assertTrue(!store.ownsKb("u2", "u2-default"));
        assertTrue(store.search("u2", "u2-default", axis(0), 5, 1.5).isEmpty());
    }

    // 造方向向量（第 i 维=1，其余 0）
    private float[] axis(int i) {
        float[] v = new float[1024];
        v[i] = 1.0f;
        return v;
    }
}
