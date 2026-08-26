package com.star.agent.skill.impl;

import com.star.agent.skill.SkillContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RentRiskSkillTest {

    private final RentRiskSkill skill = new RentRiskSkill();

    @Test
    void riskyHouseFlagsIssues() {
        String args = "{\"houses\":[{\"name\":\"A\",\"price\":800,\"area\":15,\"commute\":90,\"rating\":3}]}";
        var result = skill.execute(new SkillContext("user1", args, ""));
        assertTrue(result.success());
        assertTrue(result.content().contains("价格异常"));
        assertTrue(result.content().contains("面积过小"));
        assertTrue(result.content().contains("通勤过长"));
        assertTrue(result.content().contains("评分过低"));
    }

    @Test
    void cleanHouseNoRisk() {
        String args = "{\"houses\":[{\"name\":\"B\",\"price\":3000,\"area\":50,\"commute\":40,\"rating\":8}]}";
        var result = skill.execute(new SkillContext("user1", args, ""));
        assertTrue(result.success());
        assertTrue(result.content().contains("未发现明显风险"));
    }

    // LLM 按 meta 规范把 houses 传成字符串（{"houses":"[...]"}"）也必须能检测——同 HouseScoreSkill 同款 bug
    @Test
    void housesAsJsonString_stillDetects() {
        String args = "{\"houses\":\"[{\\\"name\\\":\\\"A\\\",\\\"price\\\":800,\\\"area\\\":15,\\\"commute\\\":90,\\\"rating\\\":3}]\"}";
        var result = skill.execute(new SkillContext("user1", args, ""));
        assertTrue(result.success(), "字符串形式的房源列表也应能检测");
        assertTrue(result.content().contains("价格异常"));
    }
}