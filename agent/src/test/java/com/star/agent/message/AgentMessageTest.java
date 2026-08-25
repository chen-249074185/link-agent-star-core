package com.star.agent.message;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentMessageTest {

    @Test
    void shouldCreateAndReadFields(){
        AgentMessage msg = new AgentMessage(
                MessageSource.WECHAT,
                "wx_123",
                "北京天气",
                MediaType.TEXT,
                null,
                "msg_1001",
                Instant.parse("2026-08-12T10:00:00Z")
        );

        assertEquals(MessageSource.WECHAT, msg.source());
        assertEquals("wx_123", msg.userId());
        assertEquals("北京天气", msg.text());
        assertEquals(MediaType.TEXT, msg.mediaType());
        assertEquals("msg_1001", msg.messageId());
        assertEquals("2026-08-12T10:00:00Z", msg.timestamp().toString());
    }
}
