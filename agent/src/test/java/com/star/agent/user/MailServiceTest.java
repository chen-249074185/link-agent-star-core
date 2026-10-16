package com.star.agent.user;

import com.star.agent.infra.config.AppConfig;
import com.star.agent.infra.web.BizException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 邮件服务单测：假 JavaMailSender 验证主题/正文带验证码；未配置发信账号 → 话术不崩
class MailServiceTest {

    // 假配置：子类覆写 mailUser/mailAuthCode（AppConfig 的 getter 非 final，可覆写）
    private static AppConfig fakeConfig(String user, String authCode) {
        return new AppConfig() {
            @Override
            public String mailUser() {
                return user;
            }

            @Override
            public String mailAuthCode() {
                return authCode;
            }
        };
    }

    @Test
    void sendCode_subjectAndBodyContainCode() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
        MailService service = new MailService(sender, fakeConfig("sender@qq.com", "auth-code"));

        service.sendCode("to@qq.com", "123456");

        var captor = org.mockito.ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captor.capture());
        MimeMessage msg = captor.getValue();
        assertEquals("to@qq.com", msg.getRecipients(MimeMessage.RecipientType.TO)[0].toString());
        assertEquals("LinkAgent验证码", msg.getSubject());
        assertTrue(msg.getContent().toString().contains("123456"), "正文应含验证码");
    }

    @Test
    void sendCode_noConfig_throwsBiz() {
        JavaMailSender sender = mock(JavaMailSender.class);
        MailService service = new MailService(sender, fakeConfig("", ""));

        BizException e = assertThrows(BizException.class, () -> service.sendCode("to@qq.com", "123456"));
        assertEquals(500, e.getCode());
        verify(sender, never()).send(any(MimeMessage.class));
    }

    @Test
    void sendCode_sendFailed_throwsBiz() {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
        org.mockito.Mockito.doThrow(new org.springframework.mail.MailSendException("smtp down"))
                .when(sender).send(any(MimeMessage.class));
        MailService service = new MailService(sender, fakeConfig("sender@qq.com", "auth-code"));

        BizException e = assertThrows(BizException.class, () -> service.sendCode("to@qq.com", "123456"));
        assertEquals(500, e.getCode());
    }
}