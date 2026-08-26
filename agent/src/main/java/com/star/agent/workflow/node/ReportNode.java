package com.star.agent.workflow.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.star.agent.skill.SkillResult;
import com.star.agent.workflow.context.WorkflowContext;

import java.util.Iterator;
import java.util.Map;

// 报告节点：收尾工序——把背包里各节点结果汇总成一份最终报告文本
// 工序结果是机器可读的 JSON（下游工序要解析），拼给用户前要翻译成人话（JSON 原文很丑）
public class ReportNode implements WorkflowNode{

    private final String name;

    public ReportNode(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void execute(WorkflowContext ctx) {
        ctx.record(name);
        // 从背包取各工序结果（key 是之前 SkillNode 放进去的节点名）
        SkillResult jobs = (SkillResult) ctx.get("jobs");
        SkillResult houses = (SkillResult) ctx.get("houses");
        SkillResult commutes = (SkillResult) ctx.get("commutes");
        SkillResult risks = (SkillResult) ctx.get("risks");
        SkillResult scores = (SkillResult) ctx.get("scores");

        // StringBuilder = 拼字符串的工地，append 一块块拼，最后 toString 出成品
        StringBuilder sb = new StringBuilder();
        sb.append("【租房决策报告】\n");
        if (jobs != null) {
            sb.append("【一、片区就业分析】").append(toReadable(jobs.content())).append("\n");
        }
        if (houses != null) {
            sb.append("【二、候选房源】").append(toReadable(houses.content())).append("\n");
        }
        if (commutes != null) {
            sb.append("【三、通勤测算】").append(commutes.content()).append("\n");
        }
        if (risks != null) {
            sb.append("【四、风险检测】").append(risks.content()).append("\n");
        }
        if (scores != null) {
            sb.append("【五、加权打分排行】").append(scores.content()).append("\n");
        }

        ctx.put("report", sb.toString());   // 汇总报告放回背包
    }

    // JSON → 人话：是 JSON 就拍平成 键：值 文本（对象单行、数组编号逐条）；不是 JSON 原样返回
    // 兜底：解析失败也原样返回（机器数据出问题不能把报告搞崩）
    static String toReadable(String raw) {
        if (raw == null) {
            return "";
        }
        JsonNode node;
        try {
            node = new ObjectMapper().readTree(raw);
        } catch (Exception e) {
            return raw;
        }
        if (node.isObject()) {
            return objectToText(node);
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder();
            int i = 1;
            for (JsonNode item : node) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(i++).append(". ").append(item.isObject() ? objectToText(item) : item.asText());
            }
            return sb.toString();
        }
        return raw;
    }

    // 对象 → "城市：北京，岗位数：3200" 单行（字段保持原 JSON 顺序）
    private static String objectToText(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        boolean first = true;
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!first) sb.append("，");
            sb.append(label(e.getKey())).append("：").append(e.getValue().asText());
            first = false;
        }
        return sb.toString();
    }

    // 常见字段名中文化（未知名保留原名，不丢数据）
    private static String label(String key) {
        return switch (key) {
            case "city" -> "城市";
            case "jobs" -> "岗位数";
            case "avgSalary" -> "平均薪资";
            case "hot" -> "热度";
            case "name" -> "名称";
            case "area" -> "面积(㎡)";
            case "price" -> "月租(元)";
            case "commute" -> "通勤(分钟)";
            case "rating" -> "评分";
            default -> key;
        };
    }
}
