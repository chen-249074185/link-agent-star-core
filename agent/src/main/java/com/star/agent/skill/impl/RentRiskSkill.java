package com.star.agent.skill.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.star.agent.skill.JsonSchemaBuilder;
import com.star.agent.skill.RiskLevel;
import com.star.agent.skill.Skill;
import com.star.agent.skill.SkillContext;
import com.star.agent.skill.SkillMeta;
import com.star.agent.skill.SkillResult;
import com.star.agent.skill.SkillType;
import org.springframework.stereotype.Component;

import java.util.List;

// 房源风险检测（MAIN 主线）：输入房源信息，按规则检测潜在风险
@Component
public class RentRiskSkill implements Skill {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public SkillMeta meta() {
        return new SkillMeta(
                "rent_risk",
                "检测房源潜在风险：价格异常偏低、面积过小、通勤过长、评分过低",
                JsonSchemaBuilder.object()
                        .addString("house", "房源信息 JSON，如 {\"name\":\"A\",\"price\":3000,\"area\":50,\"commute\":40,\"rating\":8}", true)
                        .build(),
                RiskLevel.LOW,
                List.of("CHAT"),
                "LOCAL",
                SkillType.MAIN
        );
    }

    @Override
    public SkillResult execute(SkillContext ctx) {
        try {
            JsonNode node = mapper.readTree(ctx.args());
            JsonNode house = node.path("house");
            if (house.isMissingNode()) {
                return new SkillResult(false, "请提供房源信息");
            }

            String name = house.path("name").asText("该房源");
            double price = house.path("price").asDouble();
            double area = house.path("area").asDouble();
            double commute = house.path("commute").asDouble();
            double rating = house.path("rating").asDouble();

            StringBuilder sb = new StringBuilder("[" + name + "] 风险检测：\n");
            int riskCount = 0;

            if (price > 0 && price < 1500) {
                sb.append("⚠ 价格异常偏低，谨防虚假房源或二房东\n");
                riskCount++;
            }
            if (area > 0 && area < 20) {
                sb.append("⚠ 面积过小（<20平），居住舒适度低\n");
                riskCount++;
            }
            if (commute > 60) {
                sb.append("⚠ 通勤过长（>60分钟），通勤成本高\n");
                riskCount++;
            }
            if (rating > 0 && rating < 5) {
                sb.append("⚠ 评分过低（<5分），可能存在居住问题\n");
                riskCount++;
            }
            if (price > 0 && area > 0 && price / area > 100) {
                sb.append("⚠ 每平米租金过高（>100元/平）\n");
                riskCount++;
            }

            if (riskCount == 0) {
                sb.append("✔ 未发现明显风险");
            } else {
                sb.append("共发现 ").append(riskCount).append(" 项风险");
            }
            return new SkillResult(true, sb.toString());
        } catch (Exception e) {
            return new SkillResult(false, "风险检测失败: " + e.getMessage());
        }
    }
}