package com.star.agent.skill.impl;

import com.star.agent.skill.SkillContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HouseScoreSkillTest {

    private final HouseScoreSkill skill = new HouseScoreSkill();

    @Test
    void scoreListsAllHouses() {
        String args = "{\"houses\":["
                + "{\"name\":\"A\",\"price\":3000,\"area\":50,\"commute\":40,\"rating\":8},"
                + "{\"name\":\"B\",\"price\":2000,\"area\":60,\"commute\":20,\"rating\":9}"
                + "]}";
        var result = skill.execute(new SkillContext("user1", args, ""));
        assertTrue(result.success());
        assertTrue(result.content().contains("A"));
        assertTrue(result.content().contains("B"));
    }

    @Test
    void missingHouses_returnsHelp() {
        var result = skill.execute(new SkillContext("user1", "{}", ""));
        assertTrue(result.content().contains("房源"));
    }

    // LLM 按 meta 规范把 houses 传成字符串（{"houses":"[...]"}）也必须能打分——本 bug 曾导致
    // 模型每次调用 house_score 都失败（isArray() 对字符串节点恒为 false）
    @Test
    void housesAsJsonString_stillScores() {
        String args = "{\"houses\":\"["
                + "{\\\"name\\\":\\\"A\\\",\\\"price\\\":3000,\\\"area\\\":50,\\\"commute\\\":40,\\\"rating\\\":8},"
                + "{\\\"name\\\":\\\"B\\\",\\\"price\\\":2000,\\\"area\\\":60,\\\"commute\\\":20,\\\"rating\\\":9}"
                + "]\"}";
        var result = skill.execute(new SkillContext("user1", args, ""));
        assertTrue(result.success(), "字符串形式的房源列表也应能打分");
        assertTrue(result.content().contains("A"));
        assertTrue(result.content().contains("B"));
    }

    // 直接传数组本身（{"houses"} 缺省）也兼容
    @Test
    void housesAsRawArray_stillScores() {
        String args = "[{\"name\":\"A\",\"price\":3000,\"area\":50,\"commute\":40,\"rating\":8}]";
        var result = skill.execute(new SkillContext("user1", args, ""));
        assertTrue(result.success(), "直接传数组也应能打分");
    }
}