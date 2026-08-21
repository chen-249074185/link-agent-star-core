package com.star.agent.workflow.node;

import com.star.agent.skill.SkillResult;
import com.star.agent.workflow.context.WorkflowContext;

// 报告节点：收尾工序——把背包里各节点结果汇总成一份最终报告文本
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
            sb.append("【一、片区就业分析】").append(jobs.content()).append("\n");
        }
        if (houses != null) {
            sb.append("【二、候选房源】").append(houses.content()).append("\n");
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
}
