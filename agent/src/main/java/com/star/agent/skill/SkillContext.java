package com.star.agent.skill;

public record SkillContext(
        String userId,   // 谁调用的（未来限次/知识库隔离用）
        String args,     // 参数（LLM 传来的 JSON 字符串）
        String traceId   // 全链路追踪
) {
}
