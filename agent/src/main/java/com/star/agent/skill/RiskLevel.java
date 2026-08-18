package com.star.agent.skill;

// 技能的风险等级（SkillMeta 的 riskLevel 字段用）
// 治理内核据此决定是否放行：LOW 直接执行，HIGH_RISK 需用户确认后才执行
public enum RiskLevel {
    LOW,
    HIGH_RISK
}
