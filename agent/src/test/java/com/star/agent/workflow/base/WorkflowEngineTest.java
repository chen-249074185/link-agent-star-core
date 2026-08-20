package com.star.agent.workflow.base;

import com.star.agent.workflow.context.WorkflowContext;
import com.star.agent.workflow.node.WorkflowNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowEngineTest {

    // 一个只记名字、啥也不干的假节点
    private WorkflowNode fakeNode(String name) {
        return new WorkflowNode() {
            @Override public String name() { return name; }
            @Override public void execute(WorkflowContext ctx) { ctx.record(name); }
        };
    }

    // 一个故意抛异常的假节点
    private WorkflowNode throwingNode(String name) {
        return new WorkflowNode() {
            @Override public String name() { return name; }
            @Override public void execute(WorkflowContext ctx) {
                throw new IllegalStateException("炸了");
            }
        };
    }

    @Test
    void execute_runsAllNodesInOrder() {
        WorkflowEngine engine = new WorkflowEngine();
        WorkflowContext ctx = new WorkflowContext("user1", "trace1");

        engine.execute(List.of(fakeNode("A"), fakeNode("B"), fakeNode("C")), ctx);

        assertEquals(List.of("A", "B", "C"), ctx.getExecutedNodes());
    }

    @Test
    void execute_nodeThrows_continuesAndRecordsError() {
        WorkflowEngine engine = new WorkflowEngine();
        WorkflowContext ctx = new WorkflowContext("user1", "trace1");

        engine.execute(List.of(fakeNode("A"), throwingNode("B"), fakeNode("C")), ctx);

        // 挂了一个，后面继续跑
        assertEquals(List.of("A", "C"), ctx.getExecutedNodes());
        // 失败信息记进背包
        Object error = ctx.get("error");
        assertNotNull(error);
        assertTrue(error.toString().contains("B"));
    }

    @Test
    void execute_emptyList_noError() {
        WorkflowEngine engine = new WorkflowEngine();
        WorkflowContext ctx = new WorkflowContext("user1", "trace1");

        engine.execute(List.of(), ctx);

        assertTrue(ctx.getExecutedNodes().isEmpty());
        assertNull(ctx.get("error"));
    }
}
