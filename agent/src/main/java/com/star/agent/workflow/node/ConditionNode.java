package com.star.agent.workflow.node;

import com.star.agent.workflow.context.WorkflowContext;

import java.util.function.Predicate;

// 条件分支节点：判断题成立走 then，不成立走 else
public class ConditionNode implements WorkflowNode{

    private final String name;

    private final Predicate<WorkflowContext> condition;  // 判断题（输入背包 → true/false）

    private final WorkflowNode thenNode;  // 成立 → 走这条道

    private final WorkflowNode elseNode;  // 不成立 → 走那条道

    public ConditionNode(String name, Predicate<WorkflowContext> condition, WorkflowNode thenNode, WorkflowNode elseNode) {
        this.name = name;
        this.condition = condition;
        this.thenNode = thenNode;
        this.elseNode = elseNode;
    }


    @Override
    public String name() {
        return name;
    }

    @Override
    public void execute(WorkflowContext ctx) {
        ctx.record(name);
        if(condition.test(ctx)){
            thenNode.execute(ctx);
        }else{
            elseNode.execute(ctx);
        }
    }
}
