package com.star.agent.message;

import java.time.Instant;

//规定给llm的信息格式
public record AgentMessage(
        MessageSource source,
        String userId,
        String text,
        MediaType mediaType,
        byte[] mediaBytes,//media的二进制数据
        String messageId,//通道消息 id（长轮询去重用；无 id 的通道可 null）
        Instant timestamp//时间戳
) {
}
