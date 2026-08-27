package com.star.agent.user;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

// JWT 服务单测：签发/解析回环、篡改拒绝、过期拒绝、弱密钥启动拦截
class JwtServiceTest {

    private static final String SECRET = "test-secret-0123456789abcdef0123456789abcdef";   // 64 字符 ≥32 字节

    @Test
    void issueAndParse_roundTrip() {
        JwtService jwt = new JwtService(SECRET);

        String token = jwt.issue("u1");

        assertNotNull(token);
        assertEquals("u1", jwt.parseUserId(token));
    }

    @Test
    void parseUserId_noToken_returnsNull() {
        assertNull(new JwtService(SECRET).parseUserId("not-a-token"));
    }

    @Test
    void parseUserId_tampered_returnsNull() {
        JwtService jwt = new JwtService(SECRET);
        String token = jwt.issue("u1");
        String tampered = token.substring(0, token.length() - 2) + "xx";

        assertNull(jwt.parseUserId(tampered), "篡改签名应解析失败");
    }

    // 过期 token：用同一密钥手工造一个已过期的 JWT，解析必须 null
    @Test
    void parseUserId_expired_returnsNull() {
        JwtService jwt = new JwtService(SECRET);
        long past = System.currentTimeMillis() - 60_000;
        String expired = Jwts.builder()
                .subject("u1")
                .issuedAt(new Date(past - 60_000))
                .expiration(new Date(past))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertNull(jwt.parseUserId(expired), "过期 token 应解析失败");
    }

    // 弱密钥拦截：HS256 密钥 <32 字节 → 构造即抛，防止弱密钥上生产
    @Test
    void shortSecret_throwsAtConstruction() {
        assertThrows(IllegalStateException.class, () -> new JwtService("too-short"));
    }
}