package com.star.agent.adapter.wechat;

import com.star.agent.user.UserRepository;
import com.star.agent.user.domain.UserDo;
import org.springframework.stereotype.Component;

// 微信身份映射（W12 10-31 三端互通核心）：
// 微信消息入口的 userId 是微信号地址（如 o9cqxxx@im.wechat），若该微信号已绑定账号
// （users.wechat_id），映射为账号 userId —— 微信对话与网页共用同一份记忆/知识库/历史。
// 未绑定 → 返回原值（微信侧自成一派，行为与绑定前一致）
@Component
public class WechatIdentityResolver {

    private final UserRepository userRepository;

    public WechatIdentityResolver(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public String resolve(String wechatId) {
        if (wechatId == null || wechatId.isBlank()) {
            return wechatId;
        }
        UserDo bound = userRepository.findByWechatId(wechatId);
        return bound == null ? wechatId : bound.getUserId();
    }
}