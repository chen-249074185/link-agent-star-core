package com.star.agent.service;

import com.star.agent.llm.ChatResponse;
import com.star.agent.llm.LlmClient;
import com.star.agent.llm.ToolCall;
import com.star.agent.skill.impl.WeatherSkill;
import com.star.agent.tool.ToolDispatcher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatServiceTest {

    private final LlmClient llmClient = mock(LlmClient.class);
    private final ToolDispatcher dispatcher = new ToolDispatcher(List.of(new WeatherSkill("http://localhost:1", "test")));
    private final ChatService service = new ChatService(llmClient, dispatcher, List.of(new WeatherSkill("http://localhost:1", "test")));

    @Test
    void directAnswer() {
        when(llmClient.chat(anyList(), anyList()))
                .thenReturn(new ChatResponse("你好！", List.of()));

        assertEquals("你好！", service.chat("你好"));
    }

    @Test
    void toolCallThenAnswer() {
        when(llmClient.chat(anyList(), anyList()))
                .thenReturn(new ChatResponse("", List.of(
                        new ToolCall("call_1", "get_weather", "{\"city\":\"北京\"}")
                )))
                .thenReturn(new ChatResponse("北京今天晴，25℃", List.of()));

        assertEquals("北京今天晴，25℃", service.chat("北京天气"));
    }
}
