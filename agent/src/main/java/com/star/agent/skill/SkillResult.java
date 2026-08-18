package com.star.agent.skill;

public record SkillResult(
        boolean success,   // 是否成功
        String content     // 返回给模型的文本
) {
}
