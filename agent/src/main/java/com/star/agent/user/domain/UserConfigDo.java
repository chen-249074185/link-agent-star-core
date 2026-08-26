package com.star.agent.user.domain;

import java.time.LocalDateTime;

// 用户配置：user_config 表（每用户一行，user_id 主键）
// 当前存猎聘 Token（用户私有的猎聘授权，每人自己的账号；调用猎聘工具时按 userId 取用）
public class UserConfigDo {

    private String userId;         // 微信用户 ID（from_user_id），天然唯一身份
    private String liepinToken;    // 该用户自己的猎聘 Token
    private LocalDateTime updatedAt;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getLiepinToken() {
        return liepinToken;
    }

    public void setLiepinToken(String liepinToken) {
        this.liepinToken = liepinToken;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}