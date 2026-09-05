-- ===== 星链智核 MySQL 初始化（全新环境 docker-compose 自动执行）=====
-- 与 mapper XML 列一一对应（W14 11-12 从 DAO 推导，勿随意改列名）
CREATE DATABASE IF NOT EXISTS linkagent CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE linkagent;

-- 短期记忆：对话流水（W6 双写落库，session_id 方案 B：消息显式归属会话）
CREATE TABLE IF NOT EXISTS conversation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    role VARCHAR(16) NOT NULL,
    content TEXT,
    session_id VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    KEY idx_session (session_id),
    KEY idx_user_time (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 每日摘要：LLM 压缩当天对话要点（UNIQUE 防重复压缩）
CREATE TABLE IF NOT EXISTS daily_summary (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    summary_date DATE NOT NULL,
    content TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_date (user_id, summary_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 用户体系（W12；MySQL 8 的 user 是保留字，表名用 users）
CREATE TABLE IF NOT EXISTS users (
    user_id VARCHAR(64) PRIMARY KEY,
    username VARCHAR(64) UNIQUE,
    password_hash VARCHAR(100),
    email VARCHAR(128) UNIQUE,
    wechat_id VARCHAR(128) UNIQUE,
    persona VARCHAR(512),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 每用户私有配置：猎聘 Token（W11 10-23 每用户化，不返回明文）
CREATE TABLE IF NOT EXISTS user_config (
    user_id VARCHAR(64) PRIMARY KEY,
    liepin_token VARCHAR(1024),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 微信 Bot 会话（W11；resume_json 全量上下文列：4 字段 resume 必炸，10-18 根因落实）
CREATE TABLE IF NOT EXISTS wechat_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    bot_id VARCHAR(64) UNIQUE,
    user_id VARCHAR(128),
    bot_token VARCHAR(1024),
    resume_json TEXT,
    saved_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;