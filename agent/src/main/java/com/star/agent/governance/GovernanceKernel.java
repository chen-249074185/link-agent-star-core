package com.star.agent.governance;

import com.star.agent.skill.RiskLevel;
import com.star.agent.skill.Skill;
import com.star.agent.skill.SkillRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;

// 治理内核：技能执行前的"保安"，过三道关才放行
@Component
public class GovernanceKernel {

    private final SkillRegistry skillRegistry;        // 花名册
    private final ConfirmationStore confirmationStore; // 登记本
    private final Map<String, Integer> roundCount = new java.util.HashMap<>(); // 每轮工具计数

    public GovernanceKernel(SkillRegistry skillRegistry, ConfirmationStore confirmationStore) {
        this.skillRegistry = skillRegistry;
        this.confirmationStore = confirmationStore;
    }

    public CheckResult check(String toolName, String userId, String args) {
        // 关1：技能在不在花名册
        Skill skill = skillRegistry.getSkill(toolName);
        if (skill == null) {
            return CheckResult.deny("没有这个技能: " + toolName);
        }

        // 关2：HIGH_RISK 要确认过才能执行
        if (skill.meta().riskLevel() == RiskLevel.HIGH_RISK) {
            String key = userId + ":" + toolName;
            String confirm = confirmationStore.get(key);
            if (confirm == null) {
                return CheckResult.deny("该操作有风险，回复'确认'后执行");
            }
            confirmationStore.delete(key);  // 用完即弃，确认只生效一次
        }

        // 关3：一轮工具调用不超过 5 次
        int count = roundCount.getOrDefault(userId, 0) + 1;
        if (count > 5) {
            return CheckResult.deny("本轮工具调用次数已达上限");
        }
        roundCount.put(userId, count);

        return CheckResult.pass();
    }

    // 每轮对话开始前调用，清零该用户本轮计数
    public void resetRounds(String userId) {
        roundCount.remove(userId);
    }
}