package com.star.agent.skill.impl.mock;

import com.star.agent.skill.JsonSchemaBuilder;
import com.star.agent.skill.RiskLevel;
import com.star.agent.skill.Skill;
import com.star.agent.skill.SkillContext;
import com.star.agent.skill.SkillMeta;
import com.star.agent.skill.SkillResult;
import com.star.agent.skill.SkillType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

// RAG 房源检索 Mock（MAIN 主线：Workflow 房源筛选输入源；mock 先行，后续换真实 RAG MCP）
@Component
@ConditionalOnProperty(name = "linkagent.workflow.mock-enabled",havingValue = "true")
public class RagSearchSkill implements Skill {
    @Override
    public SkillMeta meta() {
        return new SkillMeta(
                "rag_search",
                "检索指定城市符合条件的房源列表（Mock 假数据）",
                JsonSchemaBuilder.object()
                        .addString("city", "城市，如 北京", true)
                        .addInteger("maxPrice", "预算上限，元/月", false)
                        .build(),
                RiskLevel.LOW,
                List.of("CHAT", "DATA"),
                "LOCAL",
                SkillType.MAIN
        );
    }

    @Override
    public SkillResult execute(SkillContext ctx) {
        // 不读真实库，直接返回编好的 3 条假房源（JSON 字符串，字段与真实版对齐）
        String mockHouses = "[{\"name\":\"海淀学院路两居\",\"area\":55,\"price\":6500,\"commute\":35,\"rating\":8},"
                + "{\"name\":\"朝阳望京单间\",\"area\":28,\"price\":3800,\"commute\":50,\"rating\":7},"
                + "{\"name\":\"昌平回龙观一居\",\"area\":42,\"price\":4200,\"commute\":60,\"rating\":6}]";
        return new SkillResult(true, mockHouses);
    }
}
