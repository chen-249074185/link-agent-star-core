package com.star.agent.message;

import java.time.Instant;

public record AgentMessage(
        MessageSource source,
        String userId,
        String text,
        MediaType mediaType,
        byte[] mediaBytes,//media的二进制数据
        Instant timestamp//时间戳
) {
}
