package com.star.agent.adapter.wechat;
import com.star.agent.adapter.wechat.dao.WechatSessionDao;
import com.star.agent.adapter.wechat.domain.WechatSessionDo;

import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

// 微信会话仓库（数据库版）：wechat_session 表读写 ResumeContext
// 语义与文件版 SessionStore 一致（save/load），Spring 链路用数据库版，手动 main 链路用文件版
// ResumeContext 的 JSON 编解码复用 SessionStore.toJson/fromJson（单一来源防逻辑漂移）
@Component
public class WechatSessionRepository {

    private final WechatSessionDao mapper;

    public WechatSessionRepository(WechatSessionDao mapper) {
        this.mapper = mapper;
    }

    // 保存会话（登录后调用）：bot_id 唯一键，同 bot 重复登录=顶号覆盖
    public void save(ResumeContext ctx) {
        LoginContext lc = ctx.getLoginContext();
        WechatSessionDo s = new WechatSessionDo();
        s.setBotId(lc.getBotId());
        s.setUserId(lc.getUserId());
        s.setBotToken(lc.getBotToken());
        try {
            s.setResumeJson(SessionStore.toJson(ctx));
        } catch (IOException e) {
            throw new IllegalStateException("会话序列化失败", e);
        }
        mapper.upsert(s);
    }

    // 恢复会话（免扫码用）；无记录或 JSON 损坏 → null（调用方转扫码）
    public ResumeContext load(String botId) {
        WechatSessionDo s = mapper.findByBotId(botId);
        if (s == null) {
            return null;
        }
        return SessionStore.fromJson(s.getResumeJson());
    }

    // 全部 botId（启动扫表多 Bot 恢复用）
    public List<String> findAllBotIds() {
        return mapper.findAll().stream().map(WechatSessionDo::getBotId).toList();
    }

    // 删除某 bot 的会话（登出命令/测试清理用）
    public void delete(String botId) {
        mapper.deleteByBotId(botId);
    }

    // 删除 saved_at 早于 days 天前的会话（过期清理）；返回删除条数
    public int deleteOlderThan(int days) {
        return mapper.deleteOlderThan(LocalDateTime.now().minusDays(days));
    }

    // 改某条会话的保存时间（测试造"旧数据"用）
    public void updateSavedAt(String botId, LocalDateTime savedAt) {
        mapper.updateSavedAt(botId, savedAt);
    }

    // 会话总数（统计/调试）
    public long count() {
        return mapper.count();
    }
}