package com.star.agent.skill.impl;

import com.star.agent.skill.Skill;
import com.star.agent.skill.SkillComposer;
import com.star.agent.skill.SkillContext;
import com.star.agent.skill.SkillRegistry;
import com.star.agent.skill.SkillResult;
import org.springframework.stereotype.Component;

import java.util.List;

// 组合编排器实现：子技能执行前的三道闸——存在性 / 深度上限 2 / 循环熔断（A→B→A）
// 治理内核（安检）与编排器（结构闸）分工：安检管风控，这里管"能不能串"
@Component
public class SkillComposerImpl implements SkillComposer {

    private static final int MAX_DEPTH = 2; // 两级编排上限

    private final SkillRegistry registry;

    public SkillComposerImpl(SkillRegistry registry) {
        this.registry = registry;
    }

    @Override
    public SkillResult compose(String skillName, String args, SkillContext ctx, int depth, List<String> chain) {
        // 闸1：深度超限（组合技能最多两级：A→B，B 内不许再编 C）
        if (depth > MAX_DEPTH) {
            return new SkillResult(false, "编排深度超限: 最多" + MAX_DEPTH + "级(" + chain + "→" + skillName + ")");
        }
        // 闸2：循环熔断（A 编 B，B 又编 A → 链上已出现过目标名，拒）
        if (chain.contains(skillName)) {
            return new SkillResult(false, "编排循环熔断: " + skillName + " 已在调用链 " + chain + " 中");
        }
        // 闸3：技能存在性
        Skill skill = registry.getSkill(skillName);
        if (skill == null) {
            return new SkillResult(false, "编排目标技能不存在: " + skillName);
        }
        // 过闸：执行子技能（args 换成这次编排传的参数，身份与追踪号沿用原工单）
        return skill.execute(new SkillContext(ctx.userId(), args, ctx.traceId()));
    }
}