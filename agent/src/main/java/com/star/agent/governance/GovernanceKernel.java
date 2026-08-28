package com.star.agent.governance;

import com.star.agent.infra.web.AdminSkillSwitch;
import com.star.agent.skill.RiskLevel;
import com.star.agent.skill.Skill;
import com.star.agent.skill.SkillRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

// 治理内核：技能执行前的"保安"，过五道关才放行
@Component
public class GovernanceKernel {

    // 单日限次上限：同一用户一天内调同一技能不超过该次数
    private static final int MAX_DAILY = 100;
    // 高危确认态有效期：5 分钟（超时需重新确认）
    private static final int CONFIRM_TTL_SECONDS = 300;

    private final SkillRegistry skillRegistry;        // 花名册
    private final ConfirmationStore confirmationStore; // 确认登记本
    private final RateLimiter rateLimiter;            // 单日限次记账本
    private final AdminSkillSwitch adminSkillSwitch;  // 管理端技能开关（可空：手动链路/旧测试无此组件）
    private final Map<String, Integer> roundCount = new java.util.HashMap<>(); // 每轮工具计数
    private final Map<String, String> pendingConfirmations = new java.util.HashMap<>(); // 待确认登记本 userId→工具名

    public GovernanceKernel(SkillRegistry skillRegistry,
                            ConfirmationStore confirmationStore,
                            RateLimiter rateLimiter) {
        this(skillRegistry, confirmationStore, rateLimiter, null);
    }

    @Autowired
    public GovernanceKernel(SkillRegistry skillRegistry,
                            ConfirmationStore confirmationStore,
                            RateLimiter rateLimiter,
                            AdminSkillSwitch adminSkillSwitch) {
        this.skillRegistry = skillRegistry;
        this.confirmationStore = confirmationStore;
        this.rateLimiter = rateLimiter;
        this.adminSkillSwitch = adminSkillSwitch;
    }

    public CheckResult check(String toolName, String userId, String args, String scene) {
        // 关1：技能在不在花名册
        Skill skill = skillRegistry.getSkill(toolName);
        if (skill == null) {
            return CheckResult.deny("没有这个技能: " + toolName);
        }

        // 关1.5：管理端停用的技能直接拒（技能开关）
        if (adminSkillSwitch != null && adminSkillSwitch.isDisabled(toolName)) {
            return CheckResult.deny("该技能已被管理员停用");
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
                pendingConfirmations.put(userId, toolName);   // 记下"谁想确认哪个工具"，用户回"确认"时放行
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

    // 用户回"确认"时调用：把待确认工具写入确认态
    // 确认态 5 分钟过期（Redis 原生 TTL / 内存版懒过期），超时需重新确认
    // 返回待确认的工具名；没有待确认项返回 null（普通"确认"消息零副作用）
    public String confirm(String userId) {
        String toolName = pendingConfirmations.remove(userId);
        if (toolName != null) {
            confirmationStore.save(userId + ":" + toolName, "yes", CONFIRM_TTL_SECONDS);
        }
        return toolName;
    }
}