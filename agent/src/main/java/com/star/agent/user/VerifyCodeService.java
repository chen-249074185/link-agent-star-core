package com.star.agent.user;

import com.star.agent.infra.web.BizException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;

// 邮箱验证码：Redis 三键管理（key 规范 linkagent:auth:*）
//   code: 6 位数字，TTL 5 分钟，校验成功即删（一次性）
//   sent: 发送限流标记，TTL 60 秒（每邮箱一分钟一条）
//   fail: 连续错误计数，TTL 10 分钟，错满 5 次锁定（发送与校验都拒）
@Component
public class VerifyCodeService {

    private static final String KEY_CODE = "linkagent:auth:code:";
    private static final String KEY_SENT = "linkagent:auth:sent:";
    private static final String KEY_FAIL = "linkagent:auth:fail:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final MailService mailService;
    private final long codeTtlSec;
    private final long sentTtlSec;
    private final long failTtlSec;
    private final int maxFail;

    @Autowired
    public VerifyCodeService(StringRedisTemplate redis, MailService mailService) {
        this(redis, mailService, 300, 60, 600, 5);
    }

    // 全参构造器供测试注入短 TTL/小阈值（now 参数化同风格：窗口可控不必真等）
    public VerifyCodeService(StringRedisTemplate redis, MailService mailService,
                             long codeTtlSec, long sentTtlSec, long failTtlSec, int maxFail) {
        this.redis = redis;
        this.mailService = mailService;
        this.codeTtlSec = codeTtlSec;
        this.sentTtlSec = sentTtlSec;
        this.failTtlSec = failTtlSec;
        this.maxFail = maxFail;
    }

    // 生成验证码并发送；超限/被锁 → BizException 话术
    public void send(String email) {
        if (Boolean.TRUE.equals(redis.hasKey(KEY_SENT + email))) {
            throw new BizException(429, "发送太频繁，请 60 秒后再试");
        }
        if (failCount(email) >= maxFail) {
            throw new BizException(429, "尝试次数过多，请 10 分钟后再试");
        }
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        redis.opsForValue().set(KEY_CODE + email, code, Duration.ofSeconds(codeTtlSec));
        mailService.sendCode(email, code);
        redis.opsForValue().set(KEY_SENT + email, "1", Duration.ofSeconds(sentTtlSec));
    }

    // 校验验证码：对→一次性删除放行；错→计数；锁/过期/错 → BizException 话术
    public void verify(String email, String code) {
        if (failCount(email) >= maxFail) {
            throw new BizException(429, "尝试次数过多，请 10 分钟后再试");
        }
        String stored = redis.opsForValue().get(KEY_CODE + email);
        if (stored == null) {
            throw new BizException(400, "验证码已过期，请重新发送");
        }
        if (!stored.equals(code)) {
            Long n = redis.opsForValue().increment(KEY_FAIL + email);
            if (n != null && n == 1) {
                redis.expire(KEY_FAIL + email, Duration.ofSeconds(failTtlSec));   // 首次计数才设过期
            }
            throw new BizException(400, "验证码错误");
        }
        redis.delete(KEY_CODE + email);
        redis.delete(KEY_FAIL + email);
    }

    private long failCount(String email) {
        String v = redis.opsForValue().get(KEY_FAIL + email);
        return v == null ? 0 : Long.parseLong(v);
    }

    // 测试清理：删某邮箱全部验证码键
    public void clear(String email) {
        redis.delete(KEY_CODE + email);
        redis.delete(KEY_SENT + email);
        redis.delete(KEY_FAIL + email);
    }
}