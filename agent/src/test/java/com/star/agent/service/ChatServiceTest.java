package com.star.agent.service;

import com.star.agent.governance.ConfirmationStore;
import com.star.agent.governance.GovernanceKernel;
import com.star.agent.governance.InMemoryConfirmationStore;
import com.star.agent.governance.InMemoryRateLimiter;
import com.star.agent.llm.ChatResponse;
import com.star.agent.llm.LlmClient;
import com.star.agent.llm.ToolCall;
import com.star.agent.skill.SkillRegistry;
import com.star.agent.skill.impl.WeatherSkill;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatServiceTest {

    private final LlmClient llmClient = mock(LlmClient.class);
    private final WeatherSkill weather = new WeatherSkill("http://localhost:1", "test");
    private final SkillRegistry registry = new SkillRegistry(List.of(weather));
    private final ConfirmationStore confirmStore = new InMemoryConfirmationStore();
    private final GovernanceKernel kernel = new GovernanceKernel(registry, confirmStore, new InMemoryRateLimiter());
    private final ChatService service = new ChatService(llmClient, registry, kernel);

    @Test
    void directAnswer() {
        when(llmClient.chat(anyList(), anyList()))
                .thenReturn(new ChatResponse("你好！", List.of()));

        assertEquals("你好！", service.chat("你好", "user1", "CHAT"));
    }

    @Test
    void toolCallThenAnswer() {
        when(llmClient.chat(anyList(), anyList()))
                .thenReturn(new ChatResponse("", List.of(
                        new ToolCall("call_1", "get_weather", "{\"city\":\"北京\"}")
                )))
                .thenReturn(new ChatResponse("北京今天晴，25℃", List.of()));

        assertEquals("北京今天晴，25℃", service.chat("北京天气", "user1", "CHAT"));
    }
}