package com.star.agent.workflow.node;

import com.star.agent.skill.SkillResult;
import com.star.agent.workflow.context.WorkflowContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 报告节点容错测试：工序失败（背包缺块）时报告不崩、缺块跳过
class ReportNodeTest {

    private WorkflowContext ctxWithAll() {
        WorkflowContext ctx = new WorkflowContext("user1", "trace1");
        ctx.put("jobs", new SkillResult(true, "就业数据"));
        ctx.put("houses", new SkillResult(true, "房源数据"));
        ctx.put("commutes", new SkillResult(true, "通勤数据"));
        ctx.put("risks", new SkillResult(true, "风险数据"));
        ctx.put("scores", new SkillResult(true, "打分数据"));
        return ctx;
    }

    @Test
    void execute_allSectionsPresent_inOrder() {
        WorkflowContext ctx = ctxWithAll();
        ReportNode node = new ReportNode("report");

        node.execute(ctx);

        String report = (String) ctx.get("report");
        assertTrue(report.startsWith("【租房决策报告】"));
        assertTrue(report.contains("【一、片区就业分析】"));
        assertTrue(report.contains("【二、候选房源】"));
        assertTrue(report.contains("【三、通勤测算】"));
        assertTrue(report.contains("【四、风险检测】"));
        assertTrue(report.contains("【五、加权打分排行】"));
    }

    @Test
    void execute_missingSomeSections_skipsWithoutCrash() {
        // 模拟工序失败：commutes/risks 没放进背包
        WorkflowContext ctx = new WorkflowContext("user1", "trace1");
        ctx.put("jobs", new SkillResult(true, "就业数据"));
        ctx.put("houses", new SkillResult(true, "房源数据"));
        ctx.put("scores", new SkillResult(true, "打分数据"));
        ReportNode node = new ReportNode("report");

        node.execute(ctx);

        String report = (String) ctx.get("report");
        assertTrue(report.contains("【一、片区就业分析】"));
        assertTrue(report.contains("【二、候选房源】"));
        assertFalse(report.contains("【三、通勤测算】"));   // 缺块段落不出现
        assertFalse(report.contains("【四、风险检测】"));
        assertTrue(report.contains("【五、加权打分排行】")); // 其余段正常出
    }

    @Test
    void execute_allMissing_onlyTitleRemains() {
        WorkflowContext ctx = new WorkflowContext("user1", "trace1");
        ReportNode node = new ReportNode("report");

        node.execute(ctx);

        String report = (String) ctx.get("report");
        assertTrue(report.startsWith("【租房决策报告】"));
        assertFalse(report.contains("【一、"));
    }

    // JSON → 人话：就业对象拍平成"城市：北京，岗位数：3200"（不再把 JSON 原文甩给用户）
    @Test
    void toReadable_jobsJson_becomesText() {
        String raw = "{\"city\":\"北京\",\"jobs\":3200,\"avgSalary\":\"18000-25000\",\"hot\":\"高\"}";

        String text = ReportNode.toReadable(raw);

        assertTrue(text.contains("城市：北京"), "字段应中文化");
        assertTrue(text.contains("岗位数：3200"));
        assertTrue(text.contains("平均薪资：18000-25000"));
        assertTrue(text.contains("热度：高"));
        assertFalse(text.contains("{\""), "不应再出现 JSON 原文");
    }

    // JSON → 人话：房源数组变成编号列表，每套一行
    @Test
    void toReadable_housesJson_becomesNumberedList() {
        String raw = "[{\"name\":\"海淀学院路两居\",\"area\":55,\"price\":6500,\"commute\":35,\"rating\":8},"
                + "{\"name\":\"朝阳望京单间\",\"area\":28,\"price\":3800,\"commute\":50,\"rating\":7}]";

        String text = ReportNode.toReadable(raw);

        assertTrue(text.startsWith("1. "), "数组应编号逐条");
        assertTrue(text.contains("名称：海淀学院路两居"), "房源字段应中文化");
        assertTrue(text.contains("月租(元)：6500"));
        assertTrue(text.contains("2. "));
        assertFalse(text.contains("["), "不应再出现 JSON 原文");
    }

    // 非 JSON 内容（通勤/风险/打分已是人话）原样透传
    @Test
    void toReadable_plainText_passesThrough() {
        assertEquals("预计通勤时间约9分钟", ReportNode.toReadable("预计通勤时间约9分钟"));
        assertEquals("房源打分排行：\nA: 66.0分", ReportNode.toReadable("房源打分排行：\nA: 66.0分"));
    }

    // 解析失败（脏数据）不崩，原样返回
    @Test
    void toReadable_brokenJson_returnsRaw() {
        assertEquals("{broken", ReportNode.toReadable("{broken"));
    }
}