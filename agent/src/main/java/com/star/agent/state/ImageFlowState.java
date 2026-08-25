package com.star.agent.state;

// 图片处理流状态机 5 态：
// IDLE 起点/终点 → WAIT_IMAGE 等第一张图 → WAIT_CONSTRAINT 等操作约束 → WAIT_SECOND_IMAGE 等第二张图 → DUAL_IMAGE_ACTION 双图执行
public enum ImageFlowState {
    IDLE,                // 空闲：起点/终点
    WAIT_IMAGE,          // 等图：用户说"开始处理"后，等第一张图
    WAIT_CONSTRAINT,     // 等约束：有图没指令，等用户说要做什么
    WAIT_SECOND_IMAGE,   // 等二图：用户暗示还有一张，等第二张图（8s 超时回退）
    DUAL_IMAGE_ACTION    // 双图操作：两张图齐了，执行对比/合成（执行完 finish 回 IDLE）
}