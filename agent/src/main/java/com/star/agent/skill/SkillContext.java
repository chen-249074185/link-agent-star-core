package com.star.agent.skill;

// 技能调用时的工单信息：谁派的活（userId）、参数（args）、追踪号（traceId）
// 每次 execute() 传入一份，技能靠它知道这次要干什么
public record SkillContext(
        String userId,   // 谁调用的（未来限次/知识库隔离用）
        String args,     // 参数（LLM 传来的 JSON 字符串）
        String traceId   // 全链路追踪
) {
}
