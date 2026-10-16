package com.star.agent.user;

import com.star.agent.infra.config.AppConfig;
import com.star.agent.infra.web.BizException;
import jakarta.mail.MessagingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

// 邮件发送：SMTP 发注册/登录验证码（发信服务可切换：163/QQ，配置见 application.properties MAIL_HOST/MAIL_PORT）
// 发信邮箱需开启 SMTP 服务拿授权码（MAIL_AUTH_CODE，非登录密码）
@Component
public class MailService {

    private static final Logger logger = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender mailSender;
    private final AppConfig config;

    public MailService(JavaMailSender mailSender, AppConfig config) {
        this.mailSender = mailSender;
        this.config = config;
    }

    // 发验证码邮件；发信配置缺失/发送失败 → BizException 话术（不崩）+ warn 日志（失败可诊断）
    public void sendCode(String email, String code) {
        if (config.mailUser().isBlank() || config.mailAuthCode().isBlank()) {
            throw new BizException(500, "邮件服务未配置，请联系管理员");
        }
        try {
            MimeMessageHelper helper = new MimeMessageHelper(mailSender.createMimeMessage(), "UTF-8");
            helper.setTo(email);
            helper.setFrom(config.mailUser());
            helper.setSubject("LinkAgent验证码");
            helper.setText("你的验证码是：" + code + "，5 分钟内有效，请勿泄露给他人。", false);
            mailSender.send(helper.getMimeMessage());
        } catch (MessagingException | MailException e) {
            logger.warn("验证码邮件发送失败: 收件人={} 原因={}", email, e.getMessage());
            throw new BizException(500, "验证码邮件发送失败，请稍后重试");
        }
    }
}