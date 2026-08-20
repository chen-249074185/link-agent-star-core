package com.star.agent.workflow.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WorkflowContextTest {

    @Test
    void putAndGet_roundTrip() {
        WorkflowContext ctx = new WorkflowContext("user1", "trace123");
        ctx.put("houses", "三套房源");
        assertEquals("三套房源", ctx.get("houses"));
    }

    @Test
    void getMissingKey_returnsNull() {
        WorkflowContext ctx = new WorkflowContext("user1", "trace123");
        assertNull(ctx.get("nope"));
    }

    @Test
    void record_appendsInOrder() {
        WorkflowContext ctx = new WorkflowContext("user1", "trace123");
        ctx.record("A");
        ctx.record("B");
        assertEquals(java.util.List.of("A", "B"), ctx.getExecutedNodes());
    }

    @Test
    void identity_isFixed() {
        WorkflowContext ctx = new WorkflowContext("user1", "trace123");
        assertEquals("user1", ctx.getUserId());
        assertEquals("trace123", ctx.getTraceId());
    }
}
