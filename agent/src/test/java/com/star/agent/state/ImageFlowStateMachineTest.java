package com.star.agent.state;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 图片处理流状态机单测：迁移矩阵逐格断言 + 8s 超时 + 命令退出 + 多用户隔离 + 完整旅程
class ImageFlowStateMachineTest {

    private final ImageFlowStateMachine sm = new ImageFlowStateMachine();

    // 迁移矩阵逐格验证：断言 (from, event) → 目标状态（每格独立 userId，利用隔离特性避免状态累积串扰）
    @Test
    void transitionMatrix_idle() {
        assertEquals(ImageFlowState.WAIT_CONSTRAINT, sm.handle("idle_img", ImageFlowEvent.IMAGE));
        assertEquals(ImageFlowState.IDLE, sm.handle("idle_txt", ImageFlowEvent.TEXT));
        assertEquals(ImageFlowState.IDLE, sm.handle("idle_cancel", ImageFlowEvent.CANCEL));
        assertEquals(ImageFlowState.IDLE, sm.handle("idle_stop", ImageFlowEvent.STOP));
        assertEquals(ImageFlowState.WAIT_IMAGE, sm.handle("idle_restart", ImageFlowEvent.RESTART));
    }

    @Test
    void transitionMatrix_waitImage() {
        sm.handle("u1", ImageFlowEvent.RESTART);   // → WAIT_IMAGE
        assertEquals(ImageFlowState.WAIT_CONSTRAINT, sm.handle("u1", ImageFlowEvent.IMAGE));
    }

    @Test
    void transitionMatrix_waitImage_ignoresText() {
        sm.handle("u1", ImageFlowEvent.RESTART);   // → WAIT_IMAGE
        assertEquals(ImageFlowState.WAIT_IMAGE, sm.handle("u1", ImageFlowEvent.TEXT), "等图时文本应忽略");
    }

    @Test
    void transitionMatrix_waitConstraint_ignoresExtraImage() {
        sm.handle("u1", ImageFlowEvent.IMAGE);     // → WAIT_CONSTRAINT
        assertEquals(ImageFlowState.WAIT_CONSTRAINT, sm.handle("u1", ImageFlowEvent.IMAGE), "等约束时再来图应忽略（防丢约束）");
    }

    @Test
    void transitionMatrix_waitConstraint_textGoesWaitSecond() {
        sm.handle("u1", ImageFlowEvent.IMAGE);     // → WAIT_CONSTRAINT
        assertEquals(ImageFlowState.WAIT_SECOND_IMAGE, sm.handle("u1", ImageFlowEvent.TEXT), "约束文本来了→等二图（8s 窗口决定单/双图）");
    }

    @Test
    void transitionMatrix_waitSecondImage_imageGoesDualAction() {
        sm.handle("u1", ImageFlowEvent.IMAGE);     // → WAIT_CONSTRAINT
        sm.handle("u1", ImageFlowEvent.TEXT);      // → WAIT_SECOND_IMAGE
        assertEquals(ImageFlowState.DUAL_IMAGE_ACTION, sm.handle("u1", ImageFlowEvent.IMAGE), "第二张图来了→双图操作");
    }

    @Test
    void transitionMatrix_waitSecondImage_ignoresText() {
        sm.handle("u1", ImageFlowEvent.IMAGE);
        sm.handle("u1", ImageFlowEvent.TEXT);      // → WAIT_SECOND_IMAGE
        assertEquals(ImageFlowState.WAIT_SECOND_IMAGE, sm.handle("u1", ImageFlowEvent.TEXT), "等二图时文本忽略（超时才是单图收尾信号）");
    }

    @Test
    void transitionMatrix_dualAction_ignoresMessages() {
        sm.handle("u1", ImageFlowEvent.IMAGE);
        sm.handle("u1", ImageFlowEvent.TEXT);      // → WAIT_SECOND_IMAGE
        sm.handle("u1", ImageFlowEvent.IMAGE);     // → DUAL_IMAGE_ACTION
        assertEquals(ImageFlowState.DUAL_IMAGE_ACTION, sm.handle("u1", ImageFlowEvent.IMAGE), "执行中新图片应忽略");
        assertEquals(ImageFlowState.DUAL_IMAGE_ACTION, sm.handle("u1", ImageFlowEvent.TEXT), "执行中文本应忽略");
    }

    // 命令退出：任何状态 CANCEL/STOP 都回 IDLE；RESTART 重开回 WAIT_IMAGE
    @Test
    void commands_exitFromAnyState() {
        // 走到双图执行态（最深状态），命令应能中断
        sm.handle("u1", ImageFlowEvent.IMAGE);
        sm.handle("u1", ImageFlowEvent.TEXT);
        sm.handle("u1", ImageFlowEvent.IMAGE);     // → DUAL_IMAGE_ACTION

        assertEquals(ImageFlowState.IDLE, sm.handle("u1", ImageFlowEvent.CANCEL));
        assertEquals(ImageFlowState.WAIT_IMAGE, sm.handle("u1", ImageFlowEvent.RESTART));
        assertEquals(ImageFlowState.IDLE, sm.handle("u1", ImageFlowEvent.STOP));
    }

    // finish：双图操作完成 → IDLE；非双图状态调 finish 无效
    @Test
    void finish_dualActionReturnsIdle() {
        sm.handle("u1", ImageFlowEvent.IMAGE);
        sm.handle("u1", ImageFlowEvent.TEXT);
        sm.handle("u1", ImageFlowEvent.IMAGE);     // → DUAL_IMAGE_ACTION

        sm.finish("u1");
        assertEquals(ImageFlowState.IDLE, sm.state("u1"));

        sm.handle("u1", ImageFlowEvent.IMAGE);     // → WAIT_CONSTRAINT
        sm.finish("u1");                           // 非双图态 finish 无效
        assertEquals(ImageFlowState.WAIT_CONSTRAINT, sm.state("u1"));
    }

    // 8s 超时：等二图超过 8s → 强制回 IDLE（按单图收尾）；checkTimeouts 返回超时用户
    @Test
    void secondImageTimeout_returnsToIdle() {
        sm.handle("u1", ImageFlowEvent.IMAGE);
        sm.handle("u1", ImageFlowEvent.TEXT);      // → WAIT_SECOND_IMAGE，开始倒计时

        long enterAt = System.currentTimeMillis();
        assertTrue(sm.checkTimeouts(enterAt + 7999).isEmpty(), "8s 内不超时");
        assertEquals(ImageFlowState.WAIT_SECOND_IMAGE, sm.state("u1"));

        List<String> timedOut = sm.checkTimeouts(enterAt + 8000);   // 刚好 8s 到点
        assertEquals(List.of("u1"), timedOut);
        assertEquals(ImageFlowState.IDLE, sm.state("u1"), "超时回 IDLE 按单图收尾");
    }

    // 多用户隔离：u1 的流程不影响 u2
    @Test
    void users_isolated() {
        sm.handle("u1", ImageFlowEvent.IMAGE);     // u1 → WAIT_CONSTRAINT
        assertEquals(ImageFlowState.IDLE, sm.state("u2"), "u2 应保持 IDLE");
        assertEquals(ImageFlowState.WAIT_CONSTRAINT, sm.state("u1"));
    }

    // 完整旅程：开始处理 → 发图 → 给约束 → 发第二张 → 双图执行 → 完成回 IDLE
    @Test
    void fullJourney_singleUser() {
        assertEquals(ImageFlowState.WAIT_IMAGE, sm.handle("u1", ImageFlowEvent.RESTART));
        assertEquals(ImageFlowState.WAIT_CONSTRAINT, sm.handle("u1", ImageFlowEvent.IMAGE));
        assertEquals(ImageFlowState.WAIT_SECOND_IMAGE, sm.handle("u1", ImageFlowEvent.TEXT));
        assertEquals(ImageFlowState.DUAL_IMAGE_ACTION, sm.handle("u1", ImageFlowEvent.IMAGE));
        sm.finish("u1");
        assertEquals(ImageFlowState.IDLE, sm.state("u1"), "双图完成回 IDLE");
    }
}