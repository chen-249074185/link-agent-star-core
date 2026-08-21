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

// UU 跑腿询价 Mock（SCENE 支线：仅作租房生活成本补充，支持独立问答；二期可换官方 uupt-mcp-server）
@Component
@ConditionalOnProperty(name = "linkagent.workflow.mock-enabled", havingValue = "true")
public class UuDeliverySkill implements Skill {

    @Override
    public SkillMeta meta() {
        return new SkillMeta(
                "uu_quote",
                "UU 跑腿配送费用询价（Mock 假数据）",
                JsonSchemaBuilder.object()
                        .addString("pickup", "取件地点，如 回龙观", true)
                        .addString("dropoff", "送达地点，如 西二旗", true)
                        .addInteger("weightKg", "重量，公斤（选填）", false)
                        .build(),
                RiskLevel.LOW,
                List.of("CHAT"),
                "LOCAL",
                SkillType.SCENE
        );
    }

    @Override
    public SkillResult execute(SkillContext ctx) {
        // 假数据：距离 distance / 价格 price / 预计送达 eta
        return new SkillResult(true, "{\"distance\":\"5.2km\",\"price\":\"12元\",\"eta\":\"35分钟\"}");
    }
}