package com.star.agent.service;

import com.star.agent.governance.ConfirmationStore;
import com.star.agent.governance.GovernanceKernel;
import com.star.agent.governance.InMemoryConfirmationStore;
import com.star.agent.governance.InMemoryRateLimiter;
import com.star.agent.llm.ChatMessage;
import com.star.agent.llm.ChatResponse;
import com.star.agent.llm.LlmClient;
import com.star.agent.llm.ModelRouter;
import com.star.agent.llm.ToolCall;
import com.star.agent.memory.ChatHistoryService;
import com.star.agent.memory.DailySummaryService;
import com.star.agent.memory.EphemeralMemory;
import com.star.agent.memory.ShortTermMemory;
import com.star.agent.memory.domain.ConversationDo;
import com.star.agent.scene.Scene;
import com.star.agent.scene.ScenePolicy;
import com.star.agent.scene.ScenePolicyConfig;
import com.star.agent.skill.RiskLevel;
import com.star.agent.skill.Skill;
import com.star.agent.skill.SkillContext;
import com.star.agent.skill.SkillMeta;
import com.star.agent.skill.SkillRegistry;
import com.star.agent.skill.SkillResult;
import com.star.agent.skill.SkillType;
import com.star.agent.skill.impl.WeatherSkill;
import com.star.agent.workflow.RentDecisionWorkflow;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
    private final EphemeralMemory memory = mock(EphemeralMemory.class);
    private final ShortTermMemory shortTermMemory = mock(ShortTermMemory.class);
    private final DailySummaryService dailySummaryService = mock(DailySummaryService.class);
    // 真实 ScenePolicyConfig：CHAT 场景 memory=ephemeral（验证记忆接入）；GROUP 场景 memory=none（验证不记）
    private final ScenePolicyConfig scenePolicyConfig = new ScenePolicyConfig();
    private final ChatService service = new ChatService(llmClient, router, registry, kernel, detector, workflow, memory, scenePolicyConfig, shortTermMemory, dailySummaryService, new com.star.agent.governance.SensitiveToolGuard(), null, null, null, null);

    public ChatServiceTest() {
        Map<Scene, ScenePolicy> policies = Map.of(
                Scene.CHAT, new ScenePolicy("deepseek-v4-flash", List.of(), "ephemeral", "casual"),
                Scene.DATA, new ScenePolicy("deepseek-v4-flash", List.of("get_weather"), "ephemeral", "concise"),
                Scene.GROUP, new ScenePolicy("deepseek-v4-flash", List.of(), "none", "polite")
        );
        scenePolicyConfig.setPolicies(policies);
    }

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

    // 瞬时记忆：ephemeral 场景先读历史再对话（历史消息拼在用户消息前面）
    @Test
    void ephemeralScene_loadsHistoryBeforeChat() {
        when(memory.load("user1")).thenReturn(List.of(
                new com.star.agent.memory.ChatEntry("user", "我想在杭州租房"),
                new com.star.agent.memory.ChatEntry("assistant", "好的，杭州哪个区？")
        ));
        when(llmClient.chat(anyList(), anyList()))
                .thenReturn(new ChatResponse("继续聊杭州", List.of()));

        service.chat("滨江区", "user1", "CHAT");

        // 历史 2 条 + 新消息 1 条 = 3 条消息发给模型
        verify(llmClient).chat(eq(List.of(
                new ChatMessage("user", "我想在杭州租房"),
                new ChatMessage("assistant", "好的，杭州哪个区？"),
                new ChatMessage("user", "滨江区")
        )), anyList());
    }

    // 瞬时记忆：对话结束后追加进记忆（用户消息 + AI 回答）+ 短期记忆双写 MySQL
    @Test
    void ephemeralScene_appendsAfterChat() {
        when(llmClient.chat(anyList(), anyList()))
                .thenReturn(new ChatResponse("杭州滨江区房价偏高，建议预算放宽", List.of()));

        service.chat("滨江区", "user1", "CHAT");

        verify(memory).append(eq("user1"), eq("滨江区"), eq("杭州滨江区房价偏高，建议预算放宽"));
        verify(shortTermMemory).saveAsync(eq("user1"), eq("user"), eq("滨江区"), anyString());
        verify(shortTermMemory).saveAsync(eq("user1"), eq("assistant"), eq("杭州滨江区房价偏高，建议预算放宽"), anyString());
    }

    // 瞬时记忆：GROUP 场景（memory=none）不读历史也不写记忆
    @Test
    void groupScene_skipsMemory() {
        when(llmClient.chat(anyList(), anyList()))
                .thenReturn(new ChatResponse("大家好", List.of()));

        service.chat("@全体成员 大家好", "user1", "GROUP");

        verify(memory, never()).load(any());
        verify(memory, never()).append(any(), any(), any());
        verify(shortTermMemory, never()).saveAsync(any(), any(), any());
    }

    // 瞬时记忆：工作流模式不写记忆（租房需求直跑 Workflow，不经 LLM）
    @Test
    void workflowMode_skipsMemory() {
        when(workflow.execute("user1", "", "北京", 6000)).thenReturn("报告");

        service.chat("在北京租房，预算6000，通勤30分钟内", "user1", "CHAT");

        verify(memory, never()).load(any());
        verify(memory, never()).append(any(), any(), any());
    }

    // 高危确认闭环：模型先调高危工具被拒 → 用户回"确认" → 模型再调 → 放行执行
    @Test
    void highRiskTool_confirmFlow() {
        // 独立链路：注册表加一个 HIGH_RISK 真技能（liepin_apply），共用字段级 confirmStore/kernel
        Skill highRisk = new Skill() {
            @Override
            public SkillMeta meta() {
                return new SkillMeta("liepin_apply", "投递简历", Map.of(),
                        RiskLevel.HIGH_RISK, List.of("CHAT"), "MCP:liepin-mcp", SkillType.SCENE);
            }

            @Override
            public SkillResult execute(SkillContext ctx) {
                return new SkillResult(true, "已投递岗位");
            }
        };
        SkillRegistry registry2 = new SkillRegistry(List.of(weather, highRisk));
        ChatService svc = new ChatService(llmClient, router, registry2, kernel, detector, workflow,
                memory, scenePolicyConfig, shortTermMemory, dailySummaryService,
                new com.star.agent.governance.SensitiveToolGuard(), null, null, null, null);

        // 第一轮：模型请求 liepin_apply → 治理内核拒绝 → 模型把拒绝转述给用户
        when(llmClient.chat(anyList(), anyList()))
                .thenReturn(new ChatResponse("", List.of(
                        new ToolCall("c1", "liepin_apply", "{\"jobId\":\"1\"}")
                )))
                .thenReturn(new ChatResponse("该操作有风险，请回复确认后执行", List.of()));
        assertEquals("该操作有风险，请回复确认后执行", svc.chat("投递这个岗位", "user1", "CHAT"));

        // 第二轮：用户回"确认" → confirm() 写入确认态 → 模型重试 → 放行执行 → 回复结果
        when(llmClient.chat(anyList(), anyList()))
                .thenReturn(new ChatResponse("", List.of(
                        new ToolCall("c2", "liepin_apply", "{\"jobId\":\"1\"}")
                )))
                .thenReturn(new ChatResponse("已投递岗位", List.of()));
        assertEquals("已投递岗位", svc.chat("确认", "user1", "CHAT"));
    }

    // 敏感工具意图守卫（实测事故：用户只说"你好"，模型调 mcp_liepin_resume 把简历全量展示）
    // 无明确意图 → 拦截，技能不执行，回复不含简历内容
    @Test
    void sensitiveTool_withoutIntent_blocked() {
        Skill resume = mock(Skill.class);
        when(resume.meta()).thenReturn(new SkillMeta("mcp_liepin_resume", "读取简历", Map.of(),
                RiskLevel.LOW, List.of("CHAT"), "MCP:liepin-mcp", SkillType.MAIN));
        when(resume.execute(any())).thenReturn(new SkillResult(true, "姓名：陈先生 学校：某大学"));
        SkillRegistry registry2 = new SkillRegistry(List.of(resume));
        GovernanceKernel kernel2 = new GovernanceKernel(registry2, confirmStore, new InMemoryRateLimiter());
        ChatService svc = new ChatService(llmClient, router, registry2, kernel2, detector, workflow,
                memory, scenePolicyConfig, shortTermMemory, dailySummaryService,
                new com.star.agent.governance.SensitiveToolGuard(), null, null, null, null);

        when(llmClient.chat(anyList(), anyList()))
                .thenReturn(new ChatResponse("", List.of(new ToolCall("c1", "mcp_liepin_resume", "{}"))))
                .thenReturn(new ChatResponse("你好！", List.of()));

        String result = svc.chat("你好", "user1", "CHAT");

        assertEquals("你好！", result);
        verify(resume, never()).execute(any());   // 技能根本没执行，私人信息未泄露
    }

    // 用户明确要求查看简历 → 放行执行
    @Test
    void sensitiveTool_withIntent_executed() {
        Skill resume = mock(Skill.class);
        when(resume.meta()).thenReturn(new SkillMeta("mcp_liepin_resume", "读取简历", Map.of(),
                RiskLevel.LOW, List.of("CHAT"), "MCP:liepin-mcp", SkillType.MAIN));
        when(resume.execute(any())).thenReturn(new SkillResult(true, "姓名：陈先生"));
        SkillRegistry registry2 = new SkillRegistry(List.of(resume));
        GovernanceKernel kernel2 = new GovernanceKernel(registry2, confirmStore, new InMemoryRateLimiter());
        ChatService svc = new ChatService(llmClient, router, registry2, kernel2, detector, workflow,
                memory, scenePolicyConfig, shortTermMemory, dailySummaryService,
                new com.star.agent.governance.SensitiveToolGuard(), null, null, null, null);

        when(llmClient.chat(anyList(), anyList()))
                .thenReturn(new ChatResponse("", List.of(new ToolCall("c1", "mcp_liepin_resume", "{}"))))
                .thenReturn(new ChatResponse("你的简历信息：姓名：陈先生", List.of()));

        String result = svc.chat("帮我看看我的简历", "user1", "CHAT");

        assertEquals("你的简历信息：姓名：陈先生", result);
        verify(resume).execute(any());   // 明确要求 → 正常执行
    }

    // ==================== SSE 流式 + 工具循环合流（W12 10-30） ====================
    // DATA 场景（有工具白名单）→ 走工具循环：工具订单静默执行 → 回调状态事件 → 正文流式输出
    @Test
    void streamWithTools_toolLoopExecutesSilently() {
        // 独立链路：mock 的 get_weather 技能（verify 工具执行需要 mock，真技能无法验证）
        Skill weatherMock = mock(Skill.class);
        when(weatherMock.meta()).thenReturn(new SkillMeta("get_weather", "查天气", Map.of(),
                RiskLevel.LOW, List.of("CHAT", "DATA"), "LOCAL", SkillType.EXT));
        when(weatherMock.execute(any())).thenReturn(new SkillResult(true, "北京晴"));
        SkillRegistry reg2 = new SkillRegistry(List.of(weatherMock));
        GovernanceKernel kernel2 = new GovernanceKernel(reg2, confirmStore, new InMemoryRateLimiter());
        ChatService svc = new ChatService(llmClient, router, reg2, kernel2, detector, workflow,
                memory, scenePolicyConfig, shortTermMemory, dailySummaryService,
                new com.star.agent.governance.SensitiveToolGuard(), null, null, null, null);

        List<String> tokens = new java.util.ArrayList<>();
        List<String> statuses = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        // doAnswer 模拟真实客户端行为：正文逐 token 回调 onToken，且返回值携带完整正文
        doAnswer(inv -> {
            int n = calls.incrementAndGet();
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> onToken = inv.getArgument(2);
            if (n == 1) {
                return new ChatResponse("", List.of(new ToolCall("call_1", "get_weather", "{\"city\":\"北京\"}")));
            }
            onToken.accept("北京今天晴，25℃");
            return new ChatResponse("北京今天晴，25℃", List.of());
        }).when(llmClient).streamWithTools(anyList(), anyList(), any());
        svc.streamWithTools("北京天气", "user1", "s1", "DATA", tokens::add, statuses::add);

        assertEquals("北京今天晴，25℃", String.join("", tokens));   // 正文逐 token 流出
        assertEquals(List.of("get_weather"), statuses);              // 工具状态事件回调了一次
        verify(weatherMock).execute(any());                          // 工具真实执行过
    }

    // 流式租房需求 → 直跑工作流，报告整体推给前端，不碰 LLM
    @Test
    void streamWithTools_rentRequestRoutesToWorkflow() {
        when(workflow.execute("user1", "", "北京", 6000)).thenReturn("【租房决策报告】演示报告");

        List<String> tokens = new java.util.ArrayList<>();
        service.streamWithTools("在北京租房，预算6000，通勤30分钟内", "user1", "s1", "CHAT", tokens::add, t -> {});

        assertEquals("【租房决策报告】演示报告", String.join("", tokens));
        verify(llmClient, never()).streamWithTools(anyList(), anyList(), any());
    }

    // 无工具场景（CHAT 白名单为空）→ 退回场景路由纯流式，不进工具循环
    @Test
    void streamWithTools_noToolSceneFallsBackToPlainStream() {
        List<String> tokens = new java.util.ArrayList<>();
        service.streamWithTools("你好", null, null, "CHAT", tokens::add, t -> {});

        verify(llmClient, never()).streamWithTools(anyList(), anyList(), any());
    }

    // ==================== 会话归属解析（30 分钟滑动窗口，11-02） ====================
    // 独立链路：mock ChatHistoryService（owns 判定）+ 共用 shortTermMemory（最近消息查询）
    private final ChatHistoryService chatHistory = mock(ChatHistoryService.class);
    private final ChatService sessionSvc = new ChatService(llmClient, router, registry, kernel, detector, workflow,
            memory, scenePolicyConfig, shortTermMemory, dailySummaryService,
            new com.star.agent.governance.SensitiveToolGuard(), chatHistory, null, null, null);

    private static ConversationDo recentRow(String sessionId, LocalDateTime createdAt) {
        ConversationDo row = new ConversationDo();
        row.setUserId("user1");
        row.setSessionId(sessionId);
        row.setCreatedAt(createdAt);
        return row;
    }

    // 30 分钟内发过消息（无前端 sessionId）→ 复用最近会话，重新计时
    @Test
    void resolveSessionId_within30Minutes_reusesRecentSession() {
        when(shortTermMemory.findHistory(eq("user1"), eq(1)))
                .thenReturn(List.of(recentRow("s-recent", LocalDateTime.now().minusMinutes(5))));

        String session = sessionSvc.resolveSessionId("user1", null, false);

        assertEquals("s-recent", session);
    }

    // 距上一条消息超过 30 分钟 → 新建会话
    @Test
    void resolveSessionId_after30Minutes_opensNewSession() {
        when(shortTermMemory.findHistory(eq("user1"), eq(1)))
                .thenReturn(List.of(recentRow("s-recent", LocalDateTime.now().minusMinutes(31))));

        String session = sessionSvc.resolveSessionId("user1", null, false);

        assertNotEquals("s-recent", session);
        assertTrue(session.startsWith("s-"));
    }

    // 无历史记录 → 新建会话
    @Test
    void resolveSessionId_noHistory_opensNewSession() {
        when(shortTermMemory.findHistory(eq("user1"), eq(1))).thenReturn(List.of());

        String session = sessionSvc.resolveSessionId("user1", null, false);

        assertTrue(session.startsWith("s-"));
    }

    // 前端主动传的会话（点历史继续聊）优先级最高，不被 30 分钟窗口覆盖
    @Test
    void resolveSessionId_requestedTakesPriorityOverWindow() {
        when(chatHistory.owns("user1", "s-requested")).thenReturn(true);
        when(shortTermMemory.findHistory(eq("user1"), eq(1)))
                .thenReturn(List.of(recentRow("s-recent", LocalDateTime.now().minusMinutes(5))));

        String session = sessionSvc.resolveSessionId("user1", "s-requested", false);

        assertEquals("s-requested", session);
    }

    // 「新对话」按钮（forceNew）→ 即使 30 分钟内也强制新建，不并回旧会话
    @Test
    void resolveSessionId_forceNew_alwaysCreatesNewSession() {
        when(chatHistory.owns("user1", "s-requested")).thenReturn(true);
        when(shortTermMemory.findHistory(eq("user1"), eq(1)))
                .thenReturn(List.of(recentRow("s-recent", LocalDateTime.now().minusMinutes(5))));

        String session = sessionSvc.resolveSessionId("user1", "s-requested", true);

        assertNotEquals("s-requested", session);
        assertNotEquals("s-recent", session);
        assertTrue(session.startsWith("s-"));
    }

    // 未登录 → null（不落库）
    @Test
    void resolveSessionId_nullUserId_returnsNull() {
        assertEquals(null, sessionSvc.resolveSessionId(null, null, false));
    }
}
