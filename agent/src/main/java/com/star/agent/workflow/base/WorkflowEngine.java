package com.star.agent.workflow.base;

import com.star.agent.workflow.context.WorkflowContext;
import com.star.agent.workflow.node.WorkflowNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

// 工作流引擎：流水线总管——按顺序执行节点清单，单个节点挂了不崩，记进背包
public class WorkflowEngine {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowEngine.class);

    // 串行执行整条流水线
    public void execute(List<WorkflowNode> nodes, WorkflowContext ctx) {
        for (WorkflowNode node : nodes) {
            try {
                node.execute(ctx);   // 执行当前工序
            } catch (Exception e) {
                // 容错：工序挂了不崩，失败信息放背包，继续跑下一个
                ctx.put("error", node.name() + " 失败: " + e.getMessage());
                logger.warn("工作流节点[{}]执行失败: {}", node.name(), e.getMessage());
            }
        }
        logger.info("工作流执行完毕, 已执行工序: {}", ctx.getExecutedNodes());
    }
}
