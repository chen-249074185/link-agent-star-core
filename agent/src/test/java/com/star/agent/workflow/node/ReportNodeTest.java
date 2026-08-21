package com.star.agent.workflow.node;

import com.star.agent.skill.SkillResult;
import com.star.agent.workflow.context.WorkflowContext;
import org.junit.jupiter.api.Test;

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
}