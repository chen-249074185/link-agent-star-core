package com.star.agent.memory.domain;

import java.time.LocalDateTime;

// 短期记忆：一条永久落库的对话记录（conversation 表）
public class ConversationDo {

    private Long id;
    private String userId;    // 谁说的（多用户隔离）
    private String role;      // user / assistant / system
    private String content;   // 消息正文
    private LocalDateTime createdAt;   // 入库时间
    private String sessionId; // 所属会话（一段对话的身份证，历史分组/继续对话用）

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
}