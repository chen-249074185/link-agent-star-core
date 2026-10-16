-- ===== LinkAgent MySQL 初始化（全新环境 docker-compose 自动执行）=====
-- 与 mapper XML 列一一对应
CREATE DATABASE IF NOT EXISTS linkagent CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE linkagent;

-- 短期记忆：对话流水
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

-- 用户体系
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

-- 每用户私有配置：猎聘 Token
CREATE TABLE IF NOT EXISTS user_config (
    user_id VARCHAR(64) PRIMARY KEY,
    liepin_token VARCHAR(1024),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 微信 Bot 会话
CREATE TABLE IF NOT EXISTS wechat_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    bot_id VARCHAR(64) UNIQUE,
    user_id VARCHAR(128),
    bot_token VARCHAR(1024),
    resume_json TEXT,
    saved_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;