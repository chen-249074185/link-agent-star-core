package com.star.agent.skill;

// 技能的三层身份标签（SkillMeta 的 skillType 字段用）
// 决定技能能否进入租房决策 Workflow：MAIN 主线参与编排，SCENE 支线可选调用，EXT 扩展不进流程
public enum SkillType {
    MAIN,
    SCENE,
    EXT
}
