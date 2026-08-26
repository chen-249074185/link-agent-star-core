package com.star.agent.memory.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

// 每日压缩摘要：某用户某天的对话要点（LLM 提炼，跨天恢复记忆时给 AI 看）
public class DailySummaryDo {

    private Long id;
    private String userId;
    private LocalDate summaryDate;    // 哪天对话的摘要
    private String content;           // 摘要正文（LLM 输出）
    private LocalDateTime createdAt;

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

    public LocalDate getSummaryDate() {
        return summaryDate;
    }

    public void setSummaryDate(LocalDate summaryDate) {
        this.summaryDate = summaryDate;
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
}