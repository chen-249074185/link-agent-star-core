package com.star.agent.governance;

import com.star.agent.skill.RiskLevel;
import com.star.agent.skill.Skill;
import com.star.agent.skill.SkillMeta;
import com.star.agent.skill.SkillRegistry;
import com.star.agent.skill.SkillType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GovernanceKernelTest {

    // 造一个假技能：风险等级可指定（SkillMeta 是 record，直接 new 真的）
    private Skill mockSkill(RiskLevel level, String name) {
        Skill skill = mock(Skill.class);
        SkillMeta meta = new SkillMeta(
                name, "测试技能", java.util.Map.of(), level,
                java.util.List.of(), "LOCAL", SkillType.EXT);
        when(skill.meta()).thenReturn(meta);
        return skill;
    }

    @Test
    void skillNotFound_isDenied() {
        SkillRegistry registry = mock(SkillRegistry.class);
        ConfirmationStore store = mock(ConfirmationStore.class);
        when(registry.getSkill("ghost")).thenReturn(null);

        GovernanceKernel kernel = new GovernanceKernel(registry, store);

        CheckResult result = kernel.check("ghost", "user1", "{}");
        assertFalse(result.allowed());
        assertTrue(result.reason().contains("没有这个技能"));
    }

    @Test
    void lowRisk_skipsConfirmation() {
        SkillRegistry registry = mock(SkillRegistry.class);
        ConfirmationStore store = mock(ConfirmationStore.class);
        Skill skill = mockSkill(RiskLevel.LOW, "get_weather");
        when(registry.getSkill("get_weather")).thenReturn(skill);

        GovernanceKernel kernel = new GovernanceKernel(registry, store);

        CheckResult result = kernel.check("get_weather", "user1", "{\"city\":\"北京\"}");
        assertTrue(result.allowed());
        verify(store, never()).get(anyString());
    }

    @Test
    void highRisk_withoutConfirmation_isDenied() {
        SkillRegistry registry = mock(SkillRegistry.class);
        ConfirmationStore store = mock(ConfirmationStore.class);
        Skill skill = mockSkill(RiskLevel.HIGH_RISK, "liepin_apply");
        when(registry.getSkill("liepin_apply")).thenReturn(skill);
        when(store.get("user1:liepin_apply")).thenReturn(null);

        GovernanceKernel kernel = new GovernanceKernel(registry, store);

        CheckResult result = kernel.check("liepin_apply", "user1", "{}");
        assertFalse(result.allowed());
        assertTrue(result.reason().contains("确认"));
    }

    @Test
    void highRisk_withConfirmation_isAllowedAndConsumed() {
        SkillRegistry registry = mock(SkillRegistry.class);
        ConfirmationStore store = mock(ConfirmationStore.class);
        Skill skill = mockSkill(RiskLevel.HIGH_RISK, "liepin_apply");
        when(registry.getSkill("liepin_apply")).thenReturn(skill);
        when(store.get("user1:liepin_apply")).thenReturn("yes");

        GovernanceKernel kernel = new GovernanceKernel(registry, store);

        CheckResult result = kernel.check("liepin_apply", "user1", "{}");
        assertTrue(result.allowed());
        verify(store).delete("user1:liepin_apply");
    }

    @Test
    void overStepLimit_isDenied() {
        SkillRegistry registry = mock(SkillRegistry.class);
        ConfirmationStore store = mock(ConfirmationStore.class);
        Skill skill = mockSkill(RiskLevel.LOW, "get_weather");
        when(registry.getSkill("get_weather")).thenReturn(skill);

        GovernanceKernel kernel = new GovernanceKernel(registry, store);

        for (int i = 0; i < 5; i++) {
            CheckResult r = kernel.check("get_weather", "user1", "{}");
            assertTrue(r.allowed());
        }
        CheckResult result = kernel.check("get_weather", "user1", "{}");
        assertFalse(result.allowed());
        assertTrue(result.reason().contains("上限"));
    }

    @Test
    void resetRounds_clearsCount() {
        SkillRegistry registry = mock(SkillRegistry.class);
        ConfirmationStore store = mock(ConfirmationStore.class);
        Skill skill = mockSkill(RiskLevel.LOW, "get_weather");
        when(registry.getSkill("get_weather")).thenReturn(skill);

        GovernanceKernel kernel = new GovernanceKernel(registry, store);

        for (int i = 0; i < 5; i++) {
            kernel.check("get_weather", "user1", "{}");
        }
        assertFalse(kernel.check("get_weather", "user1", "{}").allowed());

        kernel.resetRounds("user1");
        assertTrue(kernel.check("get_weather", "user1", "{}").allowed());
    }
}