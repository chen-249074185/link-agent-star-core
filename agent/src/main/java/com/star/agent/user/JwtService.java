package com.star.agent.user;

import com.star.agent.infra.config.AppConfig;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

// JWT 签发/解析（jjwt 0.12，HS256）：subject = userId（贯穿对话/知识库/记忆的唯一身份）
// 密钥从 .env 的 JWT_SECRET 读（≥32 字节），过期 7 天
@Component
public class JwtService {

    private static final long TTL_MILLIS = 7L * 24 * 3600 * 1000;

    private final SecretKey key;

    @Autowired
    public JwtService(AppConfig config) {
        this(config.jwtSecret());
    }

    // 测试构造器：直接传密钥字符串；太短/为空 → 启动即报错（防止弱密钥上生产）
    public JwtService(String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET 未配置或长度不足（HS256 需 ≥32 字节），请在 .env 配置");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String issue(String userId) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(userId)
                .issuedAt(new Date(now))
                .expiration(new Date(now + TTL_MILLIS))
                .signWith(key)
                .compact();
    }

    // 解析取 userId；伪造/篡改/过期 → null（调用方按未登录处理，不抛）
    public String parseUserId(String token) {
        try {
            return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload().getSubject();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}