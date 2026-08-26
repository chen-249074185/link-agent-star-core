package com.star.agent.skill.impl.mock;

import com.star.agent.skill.SkillContext;
import com.star.agent.skill.SkillResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Mock 资料库读文件测试：按城市路由 rag_data/houses 与 rag_data/jobs，缺文件返回提示话术
// 数据文件化（参照夏令营 rag_data 目录）：加城市=加文件，代码零改动
class MockDataFileTest {

    private final RagSearchSkill rag = new RagSearchSkill();
    private final LiepinSkill liepin = new LiepinSkill();

    @Test
    void rag_returnsCityFileContent() {
        SkillResult r = rag.execute(new SkillContext("u1", "{\"city\":\"北京\"}", ""));

        assertTrue(r.success());
        assertTrue(r.content().contains("海淀学院路两居"), "北京房源应来自 北京.txt");
        assertTrue(r.content().startsWith("["), "文件内容是 JSON 数组（下游可解析）");
    }

    @Test
    void rag_unknownCity_returnsHelp() {
        SkillResult r = rag.execute(new SkillContext("u1", "{\"city\":\"不存在城\"}", ""));

        assertFalse(r.success());
        assertTrue(r.content().contains("暂未收录"), "缺文件应提示未收录而非报错");
    }

    @Test
    void liepin_returnsCityFileContent() {
        SkillResult r = liepin.execute(new SkillContext("u1", "{\"city\":\"上海\"}", ""));

        assertTrue(r.success());
        assertTrue(r.content().contains("上海"), "上海就业数据应来自 上海.txt");
        assertTrue(r.content().contains("2800"));
    }

    @Test
    void liepin_unknownCity_returnsHelp() {
        SkillResult r = liepin.execute(new SkillContext("u1", "{\"city\":\"不存在城\"}", ""));

        assertFalse(r.success());
        assertTrue(r.content().contains("暂未收录"));
    }
}