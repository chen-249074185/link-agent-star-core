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
}