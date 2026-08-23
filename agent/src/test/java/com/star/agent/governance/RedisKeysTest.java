package com.star.agent.governance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

// RedisKeys Key 规范单测：严格 linkagent:{模块}:{类型}:{标识} 五段式
class RedisKeysTest {

    @Test
    void confirmationKeyFollowsSpec() {
        assertEquals("linkagent:governance:confirmation:user1:liepin_apply",
                RedisKeys.confirmation("user1:liepin_apply"));
    }

    @Test
    void rateKeyFollowsSpec() {
        assertEquals("linkagent:governance:rate:user1:get_weather",
                RedisKeys.rate("user1:get_weather"));
    }

    @Test
    void differentKeysMakeDifferentKeys() {
        String a = RedisKeys.confirmation("user1:liepin_apply");
        String b = RedisKeys.confirmation("user1:liepin_resume");
        assertEquals(false, a.equals(b));
    }
}