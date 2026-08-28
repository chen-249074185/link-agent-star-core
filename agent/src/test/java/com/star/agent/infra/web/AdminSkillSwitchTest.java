package com.star.agent.infra.web;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// AdminSkillSwitch 单测：Redis 正常路径 + Redis 挂掉走内存兜底（管理页不阻塞）
class AdminSkillSwitchTest {

    private static final String KEY = "linkagent:admin:disabled-skills";

    @SuppressWarnings("unchecked")
    private final SetOperations<String, String> setOps = mock(SetOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final AdminSkillSwitch skillSwitch = new AdminSkillSwitch(redis);

    @Test
    void isDisabled_readsRedisMember() {
        when(redis.opsForSet()).thenReturn(setOps);
        when(setOps.isMember(KEY, "get_weather")).thenReturn(true);

        assertTrue(skillSwitch.isDisabled("get_weather"));
        assertFalse(skillSwitch.isDisabled("other"));
    }

    @Test
    void setDisabled_addsAndRemoves() {
        when(redis.opsForSet()).thenReturn(setOps);

        skillSwitch.setDisabled("get_weather", true);
        verify(setOps).add(KEY, "get_weather");

        skillSwitch.setDisabled("get_weather", false);
        verify(setOps).remove(KEY, "get_weather");
    }

    @Test
    void disabledSkills_listsAll() {
        when(redis.opsForSet()).thenReturn(setOps);
        when(setOps.members(KEY)).thenReturn(Set.of("a", "b"));

        assertEquals(Set.of("a", "b"), skillSwitch.disabledSkills());
    }

    // Redis 挂掉（测试/本地无 Redis）：isDisabled/setDisabled 走内存兜底不抛异常
    @Test
    void redisDown_fallsBackToMemory() {
        when(redis.opsForSet()).thenThrow(new RuntimeException("connection refused"));

        assertFalse(skillSwitch.isDisabled("get_weather"));   // 兜底读不崩
        skillSwitch.setDisabled("get_weather", true);          // 兜底写不崩
        assertTrue(skillSwitch.isDisabled("get_weather"));     // 兜底读到内存值
        assertEquals(Set.of("get_weather"), skillSwitch.disabledSkills());

        skillSwitch.setDisabled("get_weather", false);
        assertFalse(skillSwitch.isDisabled("get_weather"));
    }
}