package com.star.agent.state;

// 状态机事件：通道消息/命令归一化后的事件（状态机只认事件，不认具体消息）
public enum ImageFlowEvent {
    IMAGE,        // 来了一张图片
    TEXT,         // 来了文本（约束/指令）
    CANCEL,       // 命令：取消当前流程回 IDLE
    STOP,         // 命令：停止当前流程回 IDLE（与 CANCEL 同效果，语义面向"停止"）
    RESTART       // 命令：重开流程，回到等图
}