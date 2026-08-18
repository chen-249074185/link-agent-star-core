package com.star.agent.skill;

// 技能执行完的回执：success 标记成败（治理内核据此判断降级），content 是结果文本（回填给模型）
public record SkillResult(
        boolean success,   // 是否成功
        String content     // 返回给模型的文本
) {
}
