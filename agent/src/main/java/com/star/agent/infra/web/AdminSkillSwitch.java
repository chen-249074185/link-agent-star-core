package com.star.agent.infra.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// 技能开关（管理端"技能开关"Tab 数据源，W12 10-30）：
// 被停用的技能名存 Redis Set（key linkagent:admin:disabled-skills，重启不丢）；
// Redis 不可用（测试/手动链路）→ 内存 Set 兜底，管理页不阻塞
// 治理内核 check() 关1 后查它：停用的技能直接拒（"该技能已被管理员停用"）
@Component
public class AdminSkillSwitch {

    private static final Logger logger = LoggerFactory.getLogger(AdminSkillSwitch.class);

    private static final String KEY = "linkagent:admin:disabled-skills";

    private final StringRedisTemplate redis;
    private final Set<String> fallback = ConcurrentHashMap.newKeySet();   // Redis 挂时的内存兜底

    public AdminSkillSwitch(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean isDisabled(String skillName) {
        try {
            return Boolean.TRUE.equals(redis.opsForSet().isMember(KEY, skillName));
        } catch (Exception e) {
            return fallback.contains(skillName);
        }
    }

    public void setDisabled(String skillName, boolean disabled) {
        try {
            if (disabled) {
                redis.opsForSet().add(KEY, skillName);
            } else {
                redis.opsForSet().remove(KEY, skillName);
            }
        } catch (Exception e) {
            logger.warn("技能开关写入 Redis 失败，走内存兜底: {}", e.getMessage());
            if (disabled) {
                fallback.add(skillName);
            } else {
                fallback.remove(skillName);
            }
        }
    }

    // 当前停用的全部技能名（管理端列表展示）
    public Set<String> disabledSkills() {
        try {
            Set<String> members = redis.opsForSet().members(KEY);
            return members == null ? Set.of() : Set.copyOf(members);
        } catch (Exception e) {
            return Set.copyOf(fallback);
        }
    }
}