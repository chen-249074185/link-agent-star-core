package com.star.agent.workflow.node;

import com.star.agent.workflow.context.WorkflowContext;

// 循环节点：把子节点重复执行 times 次（如批量通勤测算，有几套房算几次）
public class LoopNode implements WorkflowNode{

    private final String name;

    private final WorkflowNode child;  // 循环体：每次重复执行它

    private final int times;           // 循环次数

    public LoopNode(String name, WorkflowNode child, int times) {
        this.name = name;
        this.child = child;
        this.times = times;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void execute(WorkflowContext ctx) {
        ctx.record(name);
        for (int i = 0; i < times; i++) {
            child.execute(ctx);   // 重复执行子节点
        }
    }
}
