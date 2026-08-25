package com.star.agent.scene;

import java.util.List;

// 场景策略：一个场景的完整配置（模型/工具白名单/记忆策略/回复风格）
public record ScenePolicy(
        String model,          // 该场景用的模型名（deepseek-chat / qwen-max）
        List<String> tools,    // 工具白名单：允许调用的技能名列表，空=不调工具
        String memory,         // 记忆策略：ephemeral=瞬时 / none=不记
        String replyStyle      // 回复风格：casual/creative/professional/concise/polite
) {
}