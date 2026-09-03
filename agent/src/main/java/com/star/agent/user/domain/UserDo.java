package com.star.agent.user.domain;

import java.time.LocalDateTime;

// 用户账号：users 表（W12 用户体系，三端共用同一 userId 数据互通）
// userId 为系统生成的唯一身份（贯穿对话/知识库/记忆），username/email 是登录名，至少一个
public class UserDo {

    private String userId;          // 唯一身份（"u"+随机串），对话/知识库/记忆全按它归属
    private String username;        // 用户名（路径①注册必填，唯一，可空=纯邮箱账号）
    private String passwordHash;    // BCrypt 哈希（邮箱注册可空=免密码走验证码）
    private String email;           // QQ 邮箱（可空=未绑定，唯一）
    private String wechatId;        // 绑定的微信身份（可空=未绑定，唯一；微信对话经它映射到本账号，10-31 起）
    private String persona;         // 自定义人设文本（可空=默认；用户自由定义，11-05 起）
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getWechatId() {
        return wechatId;
    }

    public void setWechatId(String wechatId) {
        this.wechatId = wechatId;
    }

    public String getPersona() {
        return persona;
    }

    public void setPersona(String persona) {
        this.persona = persona;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}