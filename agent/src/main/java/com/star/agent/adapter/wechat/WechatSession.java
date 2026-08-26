package com.star.agent.adapter.wechat;

import java.time.LocalDateTime;

// 微信会话持久化：wechat_session 表（会话恢复数据库版，替代 sessions/ 文件版）
// 关键：resume 必须带完整 ResumeContext（cursor + 会话上下文），否则服务端不认
// → 表里除 bot_token/user_id/bot_id/saved_at 基础字段外，额外存 resume_json 全量上下文
public class WechatSession {

    private Long id;
    private String botId;      // 登录上下文里的 botId（唯一键，同 bot 重复登录=顶号覆盖）
    private String userId;     // 登录账号的 userId
    private String botToken;   // 登录 token
    private String resumeJson; // 完整 ResumeContext JSON（含 cursor + 会话上下文）
    private LocalDateTime savedAt;   // 保存时间（过期清理的判据）

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBotId() {
        return botId;
    }

    public void setBotId(String botId) {
        this.botId = botId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getBotToken() {
        return botToken;
    }

    public void setBotToken(String botToken) {
        this.botToken = botToken;
    }

    public String getResumeJson() {
        return resumeJson;
    }

    public void setResumeJson(String resumeJson) {
        this.resumeJson = resumeJson;
    }

    public LocalDateTime getSavedAt() {
        return savedAt;
    }

    public void setSavedAt(LocalDateTime savedAt) {
        this.savedAt = savedAt;
    }
}