package com.star.rag.store;

import java.util.List;

// 用户知识库服务合同（R10 显式归属）：user 表 + knowledge_base 表
// W8(09-29) 落地：替换原先 ownsKb"首个上传者认领"的隐式语义，归属由表显式记录
public interface KnowledgeBaseRepository {

    // 确保用户存在并拥有默认库（新用户自动建默认库），返回该用户默认库
    KnowledgeBase ensureUserWithDefaultKb(String userId);

    // 给用户新建一个命名知识库
    KnowledgeBase createKb(String userId, String name);

    // 列出用户全部知识库
    List<KnowledgeBase> listByUser(String userId);

    // 删库：删 knowledge_base 行 + 库内全部切片
    void deleteKb(String userId, String kbId);

    // 账号合并：fromUserId 的全部知识库并入 toUserId——
    // 默认库内容并入 to 的默认库（kb_id 冲突）；非默认库改归属保留 kb_id（全局唯一不撞名）
    void transferTo(String fromUserId, String toUserId);

    // 知识库元数据
    record KnowledgeBase(String kbId, String userId, String name) {
    }
}
