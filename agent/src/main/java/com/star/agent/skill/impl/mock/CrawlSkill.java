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

// 爬虫房源采集 Mock（MAIN 主线：Workflow 房源数据采集；mock 先行，W9 换真实 Crawl MCP）
@Component
@ConditionalOnProperty(name = "linkagent.workflow.mock-enabled",havingValue = "true")
public class CrawlSkill implements Skill {

    @Override
    public SkillMeta meta() {
        return new SkillMeta(
                "crawl_listings",
                "按城市抓取租房网站房源列表（Mock 假数据）",
                JsonSchemaBuilder.object()
                        .addString("city", "城市，如 北京", true)
                        .addString("source", "数据源，如 贝壳/58（选填）", false)
                        .build(),
                RiskLevel.LOW,
                List.of("CHAT", "DATA"),
                "LOCAL",
                SkillType.MAIN
        );
    }

    @Override
    public SkillResult execute(SkillContext ctx) {
        // 不真抓网页，直接返回编好的 2 条假房源（字段与真实版对齐：name/area/price/commute/rating）
        String mockHouses = "[{\"name\":\"海淀知春路开间\",\"area\":38,\"price\":4500,\"commute\":30,\"rating\":8},"
                + "{\"name\":\"丰台科技园两居\",\"area\":68,\"price\":5200,\"commute\":45,\"rating\":7}]";
        return new SkillResult(true, mockHouses);
    }
}
