package com.star.agent.skill.impl.mock;

import com.star.agent.skill.SkillContext;
import com.star.agent.skill.SkillResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Mock 房源资料库读文件测试：按城市路由 rag_data/houses，缺文件返回提示话术
// 数据文件化（参照夏令营 rag_data 目录）：加城市=加文件，代码零改动
// 注意：job 是用户私有不建共享库（真实走猎聘 MCP），只有 houses 是通用数据文件化
class MockDataFileTest {

    private final RagSearchSkill rag = new RagSearchSkill();

    @Test
    void rag_returnsCityFileContent() {
        SkillResult r = rag.execute(new SkillContext("u1", "{\"city\":\"北京\"}", ""));

        assertTrue(r.success());
        assertTrue(r.content().contains("月租"), "北京房源应来自 北京.txt（文本格式，下游 HouseTextParser 可解析）");
        assertTrue(r.content().contains("面积"), "文本格式含面积字段");
    }

    @Test
    void rag_unknownCity_returnsHelp() {
        SkillResult r = rag.execute(new SkillContext("u1", "{\"city\":\"不存在城\"}", ""));

        assertFalse(r.success());
        assertTrue(r.content().contains("暂未收录"), "缺文件应提示未收录而非报错");
    }
}