package com.star.agent.workflow.node;

import com.star.agent.skill.Skill;
import com.star.agent.skill.SkillContext;
import com.star.agent.skill.SkillResult;
import com.star.agent.workflow.context.WorkflowContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowNodesTest {

    // 造一个假技能：execute 直接返回指定结果
    private Skill mockSkill(String content) {
        Skill skill = mock(Skill.class);
        when(skill.execute(any(SkillContext.class)))
                .thenReturn(new SkillResult(true, content));
        return skill;
    }

    // ---------- SkillNode ----------

    @Test
    void skillNode_executesAndStoresResult() {
        Skill skill = mockSkill("通勤40分钟");
        SkillNode node = new SkillNode("commute", skill, "{}");

        WorkflowContext ctx = new WorkflowContext("user1", "trace1");
        node.execute(ctx);

        SkillResult r = (SkillResult) ctx.get("commute");
        assertTrue(r.success());
        assertEquals("通勤40分钟", r.content());
        assertTrue(ctx.getExecutedNodes().contains("commute"));
    }

    @Test
    void skillNode_passesUserIdAndTraceIdToSkill() {
        Skill skill = mockSkill("ok");
        SkillNode node = new SkillNode("s", skill, "{}");

        node.execute(new WorkflowContext("user9", "trace9"));

        verify(skill).execute(new SkillContext("user9", "{}", "trace9"));
    }

    // ---------- ConditionNode ----------

    @Test
    void conditionTrue_runsThenNode() {
        WorkflowNode thenNode = new SkillNode("then", mockSkill("走了A"), "{}");
        WorkflowNode elseNode = new SkillNode("else", mockSkill("走了B"), "{}");
        ConditionNode node = new ConditionNode("check",
                ctx -> ((SkillResult) ctx.get("risks")).success(),
                thenNode, elseNode);

        WorkflowContext ctx = new WorkflowContext("user1", "trace1");
        ctx.put("risks", new SkillResult(true, "无风险"));
        node.execute(ctx);

        assertTrue(ctx.getExecutedNodes().contains("then"));
        assertTrue(!ctx.getExecutedNodes().contains("else"));
    }

    @Test
    void conditionFalse_runsElseNode() {
        WorkflowNode thenNode = new SkillNode("then", mockSkill("走了A"), "{}");
        WorkflowNode elseNode = new SkillNode("else", mockSkill("走了B"), "{}");
        ConditionNode node = new ConditionNode("check",
                ctx -> ((SkillResult) ctx.get("risks")).success(),
                thenNode, elseNode);

        WorkflowContext ctx = new WorkflowContext("user1", "trace1");
        ctx.put("risks", new SkillResult(false, "有风险"));
        node.execute(ctx);

        assertTrue(ctx.getExecutedNodes().contains("else"));
        assertTrue(!ctx.getExecutedNodes().contains("then"));
    }

    // ---------- LoopNode ----------

    @Test
    void loopNode_runsChildTimes() {
        Skill skill = mockSkill("一次");
        WorkflowNode child = new SkillNode("step", skill, "{}");
        LoopNode node = new LoopNode("batch", child, 3);

        WorkflowContext ctx = new WorkflowContext("user1", "trace1");
        node.execute(ctx);

        assertEquals(3, ctx.getExecutedNodes().stream()
                .filter("step"::equals).count());
        verify(skill, org.mockito.Mockito.times(3)).execute(any(SkillContext.class));
    }

    // ---------- ReportNode ----------

    @Test
    void reportNode_mergesBackpackIntoReport() {
        ReportNode node = new ReportNode("report");

        WorkflowContext ctx = new WorkflowContext("user1", "trace1");
        ctx.put("houses", new SkillResult(true, "A小区"));
        ctx.put("scores", new SkillResult(true, "85分"));
        node.execute(ctx);

        String report = (String) ctx.get("report");
        assertTrue(report.contains("A小区"));
        assertTrue(report.contains("85分"));
    }
}
