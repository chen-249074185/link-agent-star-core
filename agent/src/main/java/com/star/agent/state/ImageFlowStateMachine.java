package com.star.agent.state;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// 图片处理流状态机：迁移矩阵固化"状态×事件→状态"，按 userId 隔离（多人混用不串音）
// 设计理由：
//  1) 迁移表每格一个测试用例，非法/忽略迁移显式定义，散 if 不可验证
//  2) 等二图是"可选继续"——8s 没来第二张自动回 IDLE（按单图收尾），不无限等
//  3) CANCEL/STOP 任意状态回 IDLE（用户反悔的退出通道），RESTART 重开
//  4) 状态机只管状态迁移，不执行业务动作：进入 DUAL_IMAGE_ACTION 由调用方执行双图操作后调 finish()
public class ImageFlowStateMachine {

    public static final long SECOND_IMAGE_TIMEOUT_MS = 8000;   // 双图 8s 超时

    // 迁移矩阵：from 状态 × 事件 → 目标状态；null 表示"忽略该事件，停留在原状态"
    private static final Map<ImageFlowState, Map<ImageFlowEvent, ImageFlowState>> TRANSITIONS = buildTransitions();

    private static Map<ImageFlowState, Map<ImageFlowEvent, ImageFlowState>> buildTransitions() {
        Map<ImageFlowState, Map<ImageFlowEvent, ImageFlowState>> t = new java.util.EnumMap<>(ImageFlowState.class);
        t.put(ImageFlowState.IDLE, Map.of(
                ImageFlowEvent.IMAGE, ImageFlowState.WAIT_CONSTRAINT,   // 直接发图：有图等指令
                ImageFlowEvent.TEXT, ImageFlowState.IDLE,               // 普通文本不启动流程
                ImageFlowEvent.CANCEL, ImageFlowState.IDLE,
                ImageFlowEvent.STOP, ImageFlowState.IDLE,
                ImageFlowEvent.RESTART, ImageFlowState.WAIT_IMAGE));    // "开始处理"→ 等图
        t.put(ImageFlowState.WAIT_IMAGE, Map.of(
                ImageFlowEvent.IMAGE, ImageFlowState.WAIT_CONSTRAINT,   // 图来了 → 等约束
                ImageFlowEvent.TEXT, ImageFlowState.WAIT_IMAGE,         // 等图时文本忽略
                ImageFlowEvent.CANCEL, ImageFlowState.IDLE,
                ImageFlowEvent.STOP, ImageFlowState.IDLE,
                ImageFlowEvent.RESTART, ImageFlowState.WAIT_IMAGE));
        t.put(ImageFlowState.WAIT_CONSTRAINT, Map.of(
                ImageFlowEvent.IMAGE, ImageFlowState.WAIT_CONSTRAINT,   // 等约束时再来图：忽略（避免丢约束）
                ImageFlowEvent.TEXT, ImageFlowState.WAIT_SECOND_IMAGE,  // 约束文本来了 → 等二图（用户可说"还有一张"或直接下指令，先等 8s 决定单/双图）
                ImageFlowEvent.CANCEL, ImageFlowState.IDLE,
                ImageFlowEvent.STOP, ImageFlowState.IDLE,
                ImageFlowEvent.RESTART, ImageFlowState.WAIT_IMAGE));
        t.put(ImageFlowState.WAIT_SECOND_IMAGE, Map.of(
                ImageFlowEvent.IMAGE, ImageFlowState.DUAL_IMAGE_ACTION, // 第二张来了 → 双图操作
                ImageFlowEvent.TEXT, ImageFlowState.WAIT_SECOND_IMAGE,  // 等图时文本忽略（8s 超时才是单图收尾信号）
                ImageFlowEvent.CANCEL, ImageFlowState.IDLE,
                ImageFlowEvent.STOP, ImageFlowState.IDLE,
                ImageFlowEvent.RESTART, ImageFlowState.WAIT_IMAGE));
        t.put(ImageFlowState.DUAL_IMAGE_ACTION, Map.of(
                ImageFlowEvent.IMAGE, ImageFlowState.DUAL_IMAGE_ACTION, // 执行中：新事件忽略（先 finish）
                ImageFlowEvent.TEXT, ImageFlowState.DUAL_IMAGE_ACTION,
                ImageFlowEvent.CANCEL, ImageFlowState.IDLE,             // 命令随时可中断
                ImageFlowEvent.STOP, ImageFlowState.IDLE,
                ImageFlowEvent.RESTART, ImageFlowState.WAIT_IMAGE));
        return t;
    }

    private final Map<String, ImageFlowState> userStates = new ConcurrentHashMap<>();
    private final Map<String, Long> waitSecondImageSince = new ConcurrentHashMap<>();   // 进入等二图的时刻（超时判定）

    // 当前状态（默认 IDLE）
    public ImageFlowState state(String userId) {
        return userStates.getOrDefault(userId, ImageFlowState.IDLE);
    }

    // 事件处理：查迁移表 → 迁移（含超时起点记录）→ 返回新状态
    public ImageFlowState handle(String userId, ImageFlowEvent event) {
        ImageFlowState from = state(userId);
        ImageFlowState to = TRANSITIONS.get(from).get(event);
        if (to == null) {
            return from;   // 忽略：停留在原状态
        }
        userStates.put(userId, to);
        if (to == ImageFlowState.WAIT_SECOND_IMAGE) {
            waitSecondImageSince.put(userId, System.currentTimeMillis());   // 开始 8s 倒计时
        } else {
            waitSecondImageSince.remove(userId);
        }
        return to;
    }

    // 双图操作完成：DUAL_IMAGE_ACTION → IDLE（调用方执行完业务动作后调）
    public void finish(String userId) {
        if (state(userId) == ImageFlowState.DUAL_IMAGE_ACTION) {
            userStates.put(userId, ImageFlowState.IDLE);
        }
    }

    // 超时检查：等二图超过 8s 的用户强制回 IDLE（按单图收尾）；返回本次超时回退的用户列表
    public java.util.List<String> checkTimeouts(long now) {
        java.util.List<String> timedOut = new java.util.ArrayList<>();
        for (Map.Entry<String, Long> e : waitSecondImageSince.entrySet()) {
            if (now - e.getValue() >= SECOND_IMAGE_TIMEOUT_MS) {
                String userId = e.getKey();
                if (state(userId) == ImageFlowState.WAIT_SECOND_IMAGE) {
                    userStates.put(userId, ImageFlowState.IDLE);
                    timedOut.add(userId);
                }
                waitSecondImageSince.remove(userId);
            }
        }
        return timedOut;
    }
}