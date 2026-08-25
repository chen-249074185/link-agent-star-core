package com.star.agent.adapter;

import com.star.agent.message.AgentMessage;
import com.star.agent.message.MediaType;

import java.time.Instant;
import java.util.List;

// 防抖聚合产物：静默期攒下的"一组连续消息"打包成一条待处理输入
// 下游（状态机/处理逻辑）一次收到完整输入：图+文字说明不会散开、不会颠倒
public record PendingMessage(
        String userId,
        List<AgentMessage> messages,   // 攒下的全部消息段（顺序 = 到达顺序）
        String text,                   // 所有文本段拼合（AI 一次看到全部文字）
        boolean hasImage,              // 是否含图片（下游据此走图片处理）
        Instant firstAt,               // 首条消息时刻
        Instant lastAt                 // 最后一条消息时刻（= 静默期起点）
) {
}