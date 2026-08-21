package com.star.agent.skill;

import com.star.agent.skill.impl.SkillComposerImpl;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillComposerTest {

    // 一个只会报名字的假技能
    private Skill fakeSkill(String name) {
        return new Skill() {
            @Override public SkillMeta meta() {
                return new SkillMeta(name, "假技能", Map.of(), RiskLevel.LOW,
                        List.of("CHAT"), "LOCAL", SkillType.EXT);
            }
            @Override public SkillResult execute(SkillContext ctx) {
                return new SkillResult(true, name + " 执行完成, args=" + ctx.args());
            }
        };
    }

    // 组合技能 A 在 execute 里编排 B：模拟"组合技能内部调用"
    @Test
    void compose_normalTwoLevel_succeeds() {
        SkillComposer composer = new SkillComposerImpl(new SkillRegistry(List.of(fakeSkill("A"), fakeSkill("B"))));
        SkillContext ctx = new SkillContext("user1", "{}", "trace1");

        SkillResult r = composer.compose("B", "{\"x\":1}", ctx, 1, List.of("A"));

        assertTrue(r.success());
        // 子技能收到的是编排传入的 args，不是入口的
        assertTrue(r.content().contains("args={\"x\":1}"));
    }

    @Test
    void compose_depthOverMax_denied() {
        SkillComposer composer = new SkillComposerImpl(new SkillRegistry(List.of(fakeSkill("A"), fakeSkill("B"), fakeSkill("C"))));
        SkillContext ctx = new SkillContext("user1", "{}", "trace1");

        // A→B→C 第三级：depth=3 超限
        SkillResult r = composer.compose("C", "{}", ctx, 3, List.of("A", "B"));

        assertFalse(r.success());
        assertTrue(r.content().contains("深度超限"));
    }

    @Test
    void compose_cycleABecomesA_broken() {
        SkillComposer composer = new SkillComposerImpl(new SkillRegistry(List.of(fakeSkill("A"), fakeSkill("B"))));
        SkillContext ctx = new SkillContext("user1", "{}", "trace1");

        // A→B→A：链上已有 A，熔断
        SkillResult r = composer.compose("A", "{}", ctx, 2, List.of("A", "B"));

        assertFalse(r.success());
        assertTrue(r.content().contains("循环熔断"));
    }

    @Test
    void compose_unknownSkill_denied() {
        SkillComposer composer = new SkillComposerImpl(new SkillRegistry(List.of(fakeSkill("A"))));
        SkillContext ctx = new SkillContext("user1", "{}", "trace1");

        SkillResult r = composer.compose("NOPE", "{}", ctx, 1, List.of("A"));

        assertFalse(r.success());
        assertTrue(r.content().contains("不存在"));
    }
}