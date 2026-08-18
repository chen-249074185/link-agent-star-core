package com.star.agent.skill;

public interface Skill {
    SkillMeta meta();                     // 自报家门
    SkillResult execute(SkillContext ctx); // 执行技能
}
