package com.star.agent.user;

import com.star.agent.infra.web.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

// 验证码服务集成测试（真实 Redis，mock 邮件发送）：
// 发送存码+调邮件、60s 限流、错码计数、错满锁、一次性、过期
@SpringBootTest(properties = "linkagent.wechat.bot-enabled=false")
class VerifyCodeServiceTest {

    @Autowired
    VerifyCodeService service;

    @Autowired
    StringRedisTemplate redis;

    @MockBean
    MailService mailService;

    private final String email = "itest:auth" + UUID.randomUUID().toString().substring(0, 8) + "@qq.com";

    @AfterEach
    void tearDown() {
        service.clear(email);
    }

    // 白盒取 Redis 里存的验证码（send 内部随机生成，测试只能从键读）
    private String storedCode() {
        return redis.opsForValue().get("linkagent:auth:code:" + email);
    }

    @Test
    void send_storesCodeAndCallsMail() {
        service.send(email);

        assertNotNull(storedCode(), "Redis 应存有验证码");
        assertEquals(6, storedCode().length());
        verify(mailService).sendCode(eq(email), anyString());
    }

    @Test
    void sendTwice_withinWindow_rateLimited() {
        service.send(email);

        BizException e = assertThrows(BizException.class, () -> service.send(email));
        assertEquals(429, e.getCode(), "60s 窗口内二次发送应被限流");
    }

    @Test
    void verify_correctCode_passesOnce() {
        service.send(email);

        service.verify(email, storedCode());
        BizException e = assertThrows(BizException.class, () -> service.verify(email, storedCode()));
        assertEquals(400, e.getCode(), "一次性：校验成功即删，再次校验应报过期");
    }

    @Test
    void verify_wrongCode_countsAndLocks() {
        service.send(email);

        for (int i = 1; i <= 5; i++) {
            BizException e = assertThrows(BizException.class, () -> service.verify(email, "000000"));
            assertEquals(400, e.getCode(), "第 " + i + " 次错码应报验证码错误");
        }
        BizException locked = assertThrows(BizException.class, () -> service.verify(email, storedCode()));
        assertEquals(429, locked.getCode(), "错满 5 次后即使码对也被锁");
        BizException sendBlocked = assertThrows(BizException.class, () -> service.send(email));
        assertEquals(429, sendBlocked.getCode(), "锁定期间发送也被拒");
    }

    @Test
    void verify_noCodeSent_expired() {
        BizException e = assertThrows(BizException.class, () -> service.verify(email, "123456"));
        assertEquals(400, e.getCode());
    }
}