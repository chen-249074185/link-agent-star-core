package com.star.agent.governance;

import com.star.agent.skill.RiskLevel;
import com.star.agent.skill.Skill;
import com.star.agent.skill.SkillRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;

// 治理内核：技能执行前的"保安"，过五道关才放行
@Component
public class GovernanceKernel {

    // 单日限次上限：同一用户一天内调同一技能不超过该次数
    private static final int MAX_DAILY = 100;

    private final SkillRegistry skillRegistry;        // 花名册
    private final ConfirmationStore confirmationStore; // 确认登记本
    private final RateLimiter rateLimiter;            // 单日限次记账本
    private final Map<String, Integer> roundCount = new java.util.HashMap<>(); // 每轮工具计数

    public GovernanceKernel(SkillRegistry skillRegistry,
                            ConfirmationStore confirmationStore,
                            RateLimiter rateLimiter) {
        this.skillRegistry = skillRegistry;
        this.confirmationStore = confirmationStore;
        this.rateLimiter = rateLimiter;
    }

    public CheckResult check(String toolName, String userId, String args, String scene) {
        // 关1：技能在不在花名册
        Skill skill = skillRegistry.getSkill(toolName);
        if (skill == null) {
            return CheckResult.deny("没有这个技能: " + toolName);
        }

        // 关2：场景白名单（scenes 里不含当前场景 → 过滤，如群聊剔除 HIGH_RISK）
        if (!skill.meta().scenes().contains(scene)) {
            return CheckResult.deny("该技能在当前场景不可用");
        }

        // 关3：HIGH_RISK 要确认过才能执行
        if (skill.meta().riskLevel() == RiskLevel.HIGH_RISK) {
            String key = userId + ":" + toolName;
            String confirm = confirmationStore.get(key);
            if (confirm == null) {
                return CheckResult.deny("该操作有风险，回复'确认'后执行");
            }
            confirmationStore.delete(key);  // 用完即弃，确认只生效一次
        }

        // 关4：一轮工具调用不超过 5 次
        int count = roundCount.getOrDefault(userId, 0) + 1;
        if (count > 5) {
            return CheckResult.deny("本轮工具调用次数已达上限");
        }
        roundCount.put(userId, count);

        // 关5：单日限次
        String dailyKey = userId + ":" + toolName;
        int daily = rateLimiter.increment(dailyKey);
        if (daily > MAX_DAILY) {
            return CheckResult.deny("今日该操作已达上限，请明天再试");
        }

        return CheckResult.pass();
    }

    // 每轮对话开始前调用，清零该用户本轮计数
    public void resetRounds(String userId) {
        roundCount.remove(userId);
    }
}