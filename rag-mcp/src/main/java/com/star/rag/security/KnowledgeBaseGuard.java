package com.star.rag.security;

import com.star.rag.store.SQLiteVecStore;
import com.star.rag.store.VectorStore;

// 知识库归属校验层（R10 隔离）：接口入参 (userId,kbId)，归属不符直接拒绝 + 审计日志
// 语义：kb_id 由第一个上传内容的用户"认领"，其他用户不可读写删
// 注：W8（09-29）会建 knowledge_base 表做显式归属管理，本层保持接口不变只换实现
public class KnowledgeBaseGuard {

    private final VectorStore store;

    public KnowledgeBaseGuard(VectorStore store) {
        this.store = store;
    }

    // 校验 (userId,kbId) 归属：通过返回 null；拒绝返回错误话术（并打审计日志）
    // 审计日志走 stderr：符合 MCP stdio 协议铁律（stdout 只允许 JSON-RPC 消息）
    // 共享房源库（11-03 方案1）：全局只读，所有用户可检索引，无归属概念 → 直接放行
    public String checkAccess(String userId, String kbId) {
        if (SQLiteVecStore.isSharedKb(kbId)) {
            return null;   // 共享库：人人可读（写权限由工具层拦）
        }
        if (!store.ownsKb(userId, kbId)) {
            System.err.println("[审计] 越权访问被拒 userId=" + userId + " kbId=" + kbId);
            return "无权访问该知识库";
        }
        return null;
    }
}
