package com.star.agent.skill;

// 技能契约接口：所有技能（本地实现或 MCP 包装）必须遵守的约定
// meta() 自报家门（身份证），execute() 接单干活（输入环境信息，返回执行结果）
public interface Skill {
    SkillMeta meta();                     // 自报家门
    SkillResult execute(SkillContext ctx); // 执行技能
}
