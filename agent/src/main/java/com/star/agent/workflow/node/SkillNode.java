package com.star.agent.workflow.node;

import com.star.agent.skill.Skill;
import com.star.agent.skill.SkillContext;
import com.star.agent.skill.SkillResult;
import com.star.agent.workflow.context.WorkflowContext;

import java.util.function.Function;

// 技能节点：把任意一个已有 Skill 包成"工作流工序"
// 干活逻辑 = 从背包拿身份信息 → 造 SkillContext 工单 → 执行技能 → 结果放回背包
// 参数两个来源：固定字符串（构造时定死）或 动态提供器（运行时从背包算）
public class SkillNode implements WorkflowNode {

    private final String name;   // 节点名（放进背包的钥匙 + 执行栈的名字）

    private final Skill skill;   // 要执行的技能（接口，具体是谁由构造时决定）

    private final Function<WorkflowContext, String> argsProvider;  // 参数怎么来：固定值 或 从背包动态算

    // 固定参数构造器：永远用同一串参数（用户输入这类）
    public SkillNode(String name, Skill skill, String fixedArgs) {
        this(name, skill, ctx -> fixedArgs);   // 委托给下面的构造器，lambda 忽略背包返回固定串
    }

    // 动态参数构造器：运行时从背包算参数（上一节点结果这类）
    public SkillNode(String name, Skill skill, Function<WorkflowContext, String> argsProvider) {
        this.name = name;
        this.skill = skill;
        this.argsProvider = argsProvider;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void execute(WorkflowContext ctx) {
        String args = argsProvider.apply(ctx);   // 现场算参数：固定串 或 从背包拼 JSON
        SkillContext sc = new SkillContext(ctx.getUserId(), args, ctx.getTraceId());
        SkillResult result = skill.execute(sc);
        ctx.put(name, result);   // 把执行结果放进背包，后面的节点用 get(name) 取
        ctx.record(name);        // 记账：这道工序跑过了
    }
}
