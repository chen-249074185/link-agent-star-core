package com.star.agent.workflow.node;

import com.star.agent.workflow.context.WorkflowContext;

// 工作流节点的合同：每个工位必须"报名字"+ "干活"
// name() = 工位名牌（当背包的钥匙、进执行栈）；execute(ctx) = 干活（从背包取料、干完放回背包）
// 注意：结果不通过返回值给下一个节点，而是 ctx.put(...) 放回背包——节点之间靠背包传数据
public interface WorkflowNode {

    String name();

    void execute(WorkflowContext ctx);

}
