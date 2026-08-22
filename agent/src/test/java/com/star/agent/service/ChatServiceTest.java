package com.star.agent.service;

import com.star.agent.governance.ConfirmationStore;
import com.star.agent.governance.GovernanceKernel;
import com.star.agent.governance.InMemoryConfirmationStore;
import com.star.agent.governance.InMemoryRateLimiter;
import com.star.agent.llm.ChatResponse;
import com.star.agent.llm.LlmClient;
import com.star.agent.llm.ModelRouter;
import com.star.agent.llm.ToolCall;
import com.star.agent.skill.SkillRegistry;
import com.star.agent.skill.impl.WeatherSkill;
import com.star.agent.workflow.RentDecisionWorkflow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceTest {

    private final LlmClient llmClient = mock(LlmClient.class);
    private final WeatherSkill weather = new WeatherSkill("http://localhost:1", "test");
    private final SkillRegistry registry = new SkillRegistry(List.of(weather));
    private final ConfirmationStore confirmStore = new InMemoryConfirmationStore();
    private final GovernanceKernel kernel = new GovernanceKernel(registry, confirmStore, new InMemoryRateLimiter());
    private final RentRequestDetector detector = new RentRequestDetector();
    private final RentDecisionWorkflow workflow = mock(RentDecisionWorkflow.class);
    // 手动链路 ModelRouter：三个客户端全指向 mock（ScenePolicy 传 null 走回退，chat() 测试不受影响）
    private final ModelRouter router = new ModelRouter(llmClient, llmClient, llmClient, null);
    private final ChatService service = new ChatService(llmClient, router, registry, kernel, detector, workflow);

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

    // 双模式：完整租房需求 → 直跑工作流，不碰 LLM
    @Test
    void rentRequestRoutesToWorkflow() {
        when(workflow.execute("user1", "", "北京", 6000)).thenReturn("【租房决策报告】演示报告");

        String result = service.chat("在北京租房，预算6000，通勤30分钟内", "user1", "CHAT");

        assertEquals("【租房决策报告】演示报告", result);
        verify(llmClient, never()).chat(anyList(), anyList());  // 工作流模式不走 LLM
    }

    // 双模式：普通消息不触发工作流
    @Test
    void plainChatDoesNotTriggerWorkflow() {
        when(llmClient.chat(anyList(), anyList()))
                .thenReturn(new ChatResponse("你好！", List.of()));

        service.chat("你好", "user1", "CHAT");

        verify(workflow, never()).execute(any(), any(), any(), anyInt());
    }
}