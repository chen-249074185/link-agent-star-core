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

// 房源加权打分（MAIN 主线）：输入房源列表，按权重量化打分对比
@Component
public class HouseScoreSkill implements Skill {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public SkillMeta meta() {
        return new SkillMeta(
                "house_score",
                "给房源列表按权重加权打分（价格/面积/通勤/评分），用于量化对比",
                JsonSchemaBuilder.object()
                        .addString("houses", "房源列表 JSON，如 [{\"name\":\"A\",\"price\":3000,\"area\":50,\"commute\":40,\"rating\":8}]", true)
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
            JsonNode houses = node.path("houses");
            if (!houses.isArray() || houses.isEmpty()) {
                return new SkillResult(false, "请提供房源列表");
            }

            StringBuilder sb = new StringBuilder("房源打分排行：\n");
            for (JsonNode h : houses) {
                String name = h.path("name").asText();
                double price = h.path("price").asDouble();
                double area = h.path("area").asDouble();
                double commute = h.path("commute").asDouble();
                double rating = h.path("rating").asDouble();

                double priceScore = Math.max(0, 100 - price / 100.0);   // 10000元→0分，越便宜越高
                double areaScore = Math.min(100, area * 2);              // 50平→100分封顶
                double commuteScore = Math.max(0, 100 - commute);        // 40分钟→60分
                double ratingScore = rating * 10;                        // 8分→80分

                double total = priceScore * 0.3 + areaScore * 0.2 + commuteScore * 0.3 + ratingScore * 0.2;
                sb.append(name).append(": ").append(String.format("%.1f", total)).append("分\n");
            }
            return new SkillResult(true, sb.toString().trim());
        } catch (Exception e) {
            return new SkillResult(false, "打分失败: " + e.getMessage());
        }
    }
}