package com.star.agent.workflow.node;

import com.star.agent.skill.Skill;
import com.star.agent.skill.SkillContext;
import com.star.agent.skill.SkillResult;
import com.star.agent.workflow.context.WorkflowContext;

// 技能节点：把任意一个已有 Skill 包成"工作流工序"
// 干活逻辑 = 从背包拿身份信息 → 造 SkillContext 工单 → 执行技能 → 结果放回背包
public class SkillNode implements WorkflowNode{

    private final String name;   // 节点名（放进背包的钥匙 + 执行栈的名字）

    private final Skill skill;   // 要执行的技能（接口，具体是谁由构造时决定）

    private final String args;   // 技能的参数（JSON 字符串，构造时定死）

    public SkillNode(String name, Skill skill, String args) {
        this.name = name;
        this.skill = skill;
        this.args = args;
    }


    @Override
    public String name() {
        return name;
    }

    @Override
    public void execute(WorkflowContext ctx) {
        // 复用技能体系的工单：userId/traceId 从背包拿，参数用自己的
        SkillContext sc = new SkillContext(ctx.getUserId(),args, ctx.getTraceId());
        SkillResult result = skill.execute(sc);
        ctx.put(name,result);   // 把执行结果放进背包，后面的节点用 get(name) 取
        ctx.record(name);       // 记账：这道工序跑过了
    }
}
