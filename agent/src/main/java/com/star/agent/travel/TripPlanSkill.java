package com.star.agent.travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.star.agent.skill.JsonSchemaBuilder;
import com.star.agent.skill.RiskLevel;
import com.star.agent.skill.Skill;
import com.star.agent.skill.SkillContext;
import com.star.agent.skill.SkillMeta;
import com.star.agent.skill.SkillResult;
import com.star.agent.skill.SkillType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

// 旅游规划技能（trip_plan，EXT 层，仅自由问答可用不参与租房 Workflow）：
// 目的地 → 高德真实景点/美食 + 纯算法排程（
// ）→ 结构化行程转可读文本（聊天直接输出）
// 与 Web 规划页共用 TravelPlanService（页面要 JSON，聊天要人话，同一份编排两种出口）
@Component
public class TripPlanSkill implements Skill {

    private final TravelPlanService planService;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public TripPlanSkill(TravelPlanService planService) {
        this.planService = planService;
    }

    @Override
    public SkillMeta meta() {
        return new SkillMeta(
                "trip_plan",
                "旅游规划：为指定目的地规划 1~7 天行程（景点+午餐+美食推荐+天气+预算，每项附高德导航链接）",
                JsonSchemaBuilder.object()
                        .addString("destination", "目的地城市或景点，如 杭州、成都、大理", true)
                        .addInteger("days", "游玩天数 1~7，默认 1", false)
                        .addString("keyword", "景点关键词，默认「景点」，如 博物馆/古镇/海边", false)
                        .build(),
                RiskLevel.LOW,
                List.of("CHAT", "DATA"),
                "LOCAL",
                SkillType.EXT
        );
    }

    @Override
    public SkillResult execute(SkillContext ctx) {
        try {
            JsonNode node = mapper.readTree(ctx.args());
            String destination = node.path("destination").asText();
            if (destination.isEmpty()) {
                return new SkillResult(false, "请告诉我要规划哪个目的地");
            }
            String keyword = node.path("keyword").asText();
            int days = node.path("days").asInt(1);

            Map<String, Object> plan = planService.plan(destination, keyword, days);
            if (plan.containsKey("error")) {
                return new SkillResult(false, String.valueOf(plan.get("error")));
            }
            return new SkillResult(true, toText(plan));
        } catch (Exception e) {
            return new SkillResult(false, "旅游规划失败: " + e.getMessage());
        }
    }

    // 结构化 Map → 可读行程文本（聊天输出用；页面直接拿 JSON 不走这层）
    @SuppressWarnings("unchecked")
    private String toText(Map<String, Object> plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("🏖️ ").append(plan.get("location")).append(" 行程规划\n");
        Object weather = plan.get("weather");
        if (weather != null && !String.valueOf(weather).isBlank()) {
            sb.append("🌤️ ").append(weather).append("\n");
        }

        for (Object dayObj : (List<Object>) plan.get("dayPlan")) {
            Map<String, Object> day = (Map<String, Object>) dayObj;
            sb.append("\n📅 第 ").append(day.get("day")).append(" 天\n");
            for (Object entryObj : (List<Object>) day.get("schedule")) {
                Map<String, Object> entry = (Map<String, Object>) entryObj;
                String time = String.valueOf(entry.get("time"));
                String name = String.valueOf(entry.get("name"));
                if ("lunch".equals(entry.get("type"))) {
                    sb.append("  🕛 ").append(time).append("  午餐：").append(name);
                    Object cost = entry.get("cost");
                    if (cost != null && !String.valueOf(cost).isBlank()) {
                        sb.append("（人均 ¥").append(cost).append("）");
                    }
                    sb.append("\n");
                    continue;
                }
                sb.append("  🕘 ").append(time).append("  ").append(name)
                        .append("（建议游玩 ").append(entry.get("duration")).append("）\n");
                Object distance = entry.get("distance");
                if (distance != null && !String.valueOf(distance).isBlank()) {
                    sb.append("      📍 距中心约 ").append(distance);
                    Object address = entry.get("address");
                    if (address != null && !String.valueOf(address).isBlank()) {
                        sb.append(" · ").append(address);
                    }
                    sb.append("\n");
                }
                Object navUrl = entry.get("navUrl");
                if (navUrl != null && !String.valueOf(navUrl).isBlank()) {
                    sb.append("      🚗 [导航前往](").append(navUrl).append(")\n");
                }
            }
        }

        Object budget = plan.get("budget");
        if (budget != null) {
            sb.append("\n💰 预算参考\n");
            for (Object v : ((Map<String, Object>) budget).values()) {
                sb.append("  · ").append(v).append("\n");
            }
        }
        return sb.toString().trim();
    }
}