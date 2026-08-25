package com.star.agent.governance;

import com.star.agent.skill.RiskLevel;
import com.star.agent.skill.Skill;
import com.star.agent.skill.SkillMeta;
import com.star.agent.skill.SkillRegistry;
import com.star.agent.skill.SkillType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GovernanceKernelTest {

    // 造一个假技能：风险等级 + 场景白名单可指定（SkillMeta 是 record，直接 new 真的）
    private Skill mockSkill(RiskLevel level, String name, List<String> scenes) {
        Skill skill = mock(Skill.class);
        SkillMeta meta = new SkillMeta(
                name, "测试技能", Map.of(), level,
                scenes, "LOCAL", SkillType.EXT);
        when(skill.meta()).thenReturn(meta);
        return skill;
    }

    private GovernanceKernel newKernel(SkillRegistry registry, ConfirmationStore store, RateLimiter limiter) {
        return new GovernanceKernel(registry, store, limiter);
    }

    @Test
    void skillNotFound_isDenied() {
        SkillRegistry registry = mock(SkillRegistry.class);
        ConfirmationStore store = mock(ConfirmationStore.class);
        RateLimiter limiter = mock(RateLimiter.class);
        when(registry.getSkill("ghost")).thenReturn(null);

        GovernanceKernel kernel = newKernel(registry, store, limiter);

        CheckResult result = kernel.check("ghost", "user1", "{}", "CHAT");
        assertFalse(result.allowed());
        assertTrue(result.reason().contains("没有这个技能"));
    }

    @Test
    void lowRisk_skipsConfirmation() {
        SkillRegistry registry = mock(SkillRegistry.class);
        ConfirmationStore store = mock(ConfirmationStore.class);
        RateLimiter limiter = mock(RateLimiter.class);
        Skill skill = mockSkill(RiskLevel.LOW, "get_weather", List.of("CHAT"));
        when(registry.getSkill("get_weather")).thenReturn(skill);

        GovernanceKernel kernel = newKernel(registry, store, limiter);

        CheckResult result = kernel.check("get_weather", "user1", "{\"city\":\"北京\"}", "CHAT");
        assertTrue(result.allowed());
        verify(store, never()).get(anyString());
    }

    @Test
    void sceneNotWhitelisted_isDenied() {
        SkillRegistry registry = mock(SkillRegistry.class);
        ConfirmationStore store = mock(ConfirmationStore.class);
        RateLimiter limiter = mock(RateLimiter.class);
        Skill skill = mockSkill(RiskLevel.LOW, "get_weather", List.of("PRIVATE"));
        when(registry.getSkill("get_weather")).thenReturn(skill);

        GovernanceKernel kernel = newKernel(registry, store, limiter);

        CheckResult result = kernel.check("get_weather", "user1", "{}", "GROUP");
        assertFalse(result.allowed());
        assertTrue(result.reason().contains("场景"));
    }

    @Test
    void highRisk_withoutConfirmation_isDenied() {
        SkillRegistry registry = mock(SkillRegistry.class);
        ConfirmationStore store = mock(ConfirmationStore.class);
        RateLimiter limiter = mock(RateLimiter.class);
        Skill skill = mockSkill(RiskLevel.HIGH_RISK, "liepin_apply", List.of("CHAT"));
        when(registry.getSkill("liepin_apply")).thenReturn(skill);
        when(store.get("user1:liepin_apply")).thenReturn(null);

        GovernanceKernel kernel = newKernel(registry, store, limiter);

        CheckResult result = kernel.check("liepin_apply", "user1", "{}", "CHAT");
        assertFalse(result.allowed());
        assertTrue(result.reason().contains("确认"));
    }

    @Test
    void highRisk_withConfirmation_isAllowedAndConsumed() {
        SkillRegistry registry = mock(SkillRegistry.class);
        ConfirmationStore store = mock(ConfirmationStore.class);
        RateLimiter limiter = mock(RateLimiter.class);
        Skill skill = mockSkill(RiskLevel.HIGH_RISK, "liepin_apply", List.of("CHAT"));
        when(registry.getSkill("liepin_apply")).thenReturn(skill);
        when(store.get("user1:liepin_apply")).thenReturn("yes");

        GovernanceKernel kernel = newKernel(registry, store, limiter);

        CheckResult result = kernel.check("liepin_apply", "user1", "{}", "CHAT");
        assertTrue(result.allowed());
        verify(store).delete("user1:liepin_apply");
    }

    @Test
    void overStepLimit_isDenied() {
        SkillRegistry registry = mock(SkillRegistry.class);
        ConfirmationStore store = mock(ConfirmationStore.class);
        RateLimiter limiter = mock(RateLimiter.class);
        Skill skill = mockSkill(RiskLevel.LOW, "get_weather", List.of("CHAT"));
        when(registry.getSkill("get_weather")).thenReturn(skill);

        GovernanceKernel kernel = newKernel(registry, store, limiter);

        for (int i = 0; i < 5; i++) {
            CheckResult r = kernel.check("get_weather", "user1", "{}", "CHAT");
            assertTrue(r.allowed());
        }
        CheckResult result = kernel.check("get_weather", "user1", "{}", "CHAT");
        assertFalse(result.allowed());
        assertTrue(result.reason().contains("上限"));
    }

    @Test
    void dailyLimit_isDenied() {
        SkillRegistry registry = mock(SkillRegistry.class);
        ConfirmationStore store = mock(ConfirmationStore.class);
        RateLimiter limiter = mock(RateLimiter.class);
        Skill skill = mockSkill(RiskLevel.LOW, "get_weather", List.of("CHAT"));
        when(registry.getSkill("get_weather")).thenReturn(skill);
        when(limiter.increment("user1:get_weather")).thenReturn(101);

        GovernanceKernel kernel = newKernel(registry, store, limiter);

        CheckResult result = kernel.check("get_weather", "user1", "{}", "CHAT");
        assertFalse(result.allowed());
        assertTrue(result.reason().contains("上限"));
    }

    @Test
    void resetRounds_clearsCount() {
        SkillRegistry registry = mock(SkillRegistry.class);
        ConfirmationStore store = mock(ConfirmationStore.class);
        RateLimiter limiter = mock(RateLimiter.class);
        Skill skill = mockSkill(RiskLevel.LOW, "get_weather", List.of("CHAT"));
        when(registry.getSkill("get_weather")).thenReturn(skill);

        GovernanceKernel kernel = newKernel(registry, store, limiter);

        for (int i = 0; i < 5; i++) {
            kernel.check("get_weather", "user1", "{}", "CHAT");
        }
        assertFalse(kernel.check("get_weather", "user1", "{}", "CHAT").allowed());

        kernel.resetRounds("user1");
        assertTrue(kernel.check("get_weather", "user1", "{}", "CHAT").allowed());
    }

    // 确认闭环：高危被拒后记录待确认 → confirm() 写入确认态 → 再 check 放行且确认被消费
    @Test
    void highRisk_denyThenConfirm_allowsNextCheck() {
        SkillRegistry registry = mock(SkillRegistry.class);
        ConfirmationStore store = mock(ConfirmationStore.class);
        RateLimiter limiter = mock(RateLimiter.class);
        Skill skill = mockSkill(RiskLevel.HIGH_RISK, "liepin_apply", List.of("CHAT"));
        when(registry.getSkill("liepin_apply")).thenReturn(skill);
        when(store.get("user1:liepin_apply"))
                .thenReturn(null)          // 第一次 check：未确认 → 拒
                .thenReturn("yes");        // confirm 之后：放行

        GovernanceKernel kernel = newKernel(registry, store, limiter);

        assertFalse(kernel.check("liepin_apply", "user1", "{}", "CHAT").allowed());

        String pending = kernel.confirm("user1");
        assertEquals("liepin_apply", pending, "confirm 应返回待确认的工具名");
        verify(store).save("user1:liepin_apply", "yes", 300);

        assertTrue(kernel.check("liepin_apply", "user1", "{}", "CHAT").allowed());
        verify(store).delete("user1:liepin_apply");   // 确认用完即弃
    }

    // 确认闭环：没有待确认项时 confirm 返回 null（普通"确认"消息零副作用）
    @Test
    void confirm_withoutPending_returnsNull() {
        SkillRegistry registry = mock(SkillRegistry.class);
        ConfirmationStore store = mock(ConfirmationStore.class);
        RateLimiter limiter = mock(RateLimiter.class);

        GovernanceKernel kernel = newKernel(registry, store, limiter);

        assertNull(kernel.confirm("user1"));
        verify(store, never()).save(anyString(), anyString(), anyInt());
    }
}