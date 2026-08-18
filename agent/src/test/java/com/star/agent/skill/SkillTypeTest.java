package com.star.agent.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SkillTypeTest {

    @Test
    void threeLayersExist() {
        assertEquals(3, SkillType.values().length);
        assertEquals(SkillType.MAIN, SkillType.valueOf("MAIN"));
        assertEquals(SkillType.SCENE, SkillType.valueOf("SCENE"));
        assertEquals(SkillType.EXT, SkillType.valueOf("EXT"));
    }

    @Test
    void layersAreDistinct() {
        assertNotEquals(SkillType.MAIN, SkillType.SCENE);
        assertNotEquals(SkillType.MAIN, SkillType.EXT);
        assertNotEquals(SkillType.SCENE, SkillType.EXT);
    }
}