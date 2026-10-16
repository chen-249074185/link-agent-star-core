package com.star.agent.service;

import com.star.agent.governance.CheckResult;
import com.star.agent.governance.GovernanceKernel;
import com.star.agent.governance.SensitiveToolGuard;
import com.star.agent.infra.web.UsageStatsService;
import com.star.agent.llm.ChatMessage;
import com.star.agent.llm.ChatResponse;
import com.star.agent.llm.LlmClient;
import com.star.agent.llm.ModelRouter;
import com.star.agent.llm.ToolCall;
import com.star.agent.llm.ToolSpec;
import com.star.agent.memory.ChatEntry;
import com.star.agent.memory.ChatHistoryService;
import com.star.agent.memory.DailySummaryService;
import com.star.agent.memory.EphemeralMemory;
import com.star.agent.memory.ShortTermMemory;
import com.star.agent.memory.dao.ConversationDao;
import com.star.agent.memory.domain.ConversationDo;
import com.star.agent.scene.Scene;
import com.star.agent.scene.ScenePolicy;
import com.star.agent.scene.ScenePolicyConfig;
import com.star.agent.skill.Skill;
import com.star.agent.skill.SkillContext;
import com.star.agent.skill.SkillRegistry;
import com.star.agent.skill.SkillResult;
import com.star.agent.user.PersonaManager;
import com.star.agent.user.UserRepository;
import com.star.agent.workflow.RentDecisionWorkflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Component
public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    private final LlmClient llmClient;          // 工具循环大脑：固定 DeepSeek（工具调用稳定性优先）
    private final ModelRouter modelRouter;      // 场景路由：stream() 纯对话按 ScenePolicy 选模型
    private final SkillRegistry skillRegistry;
    private final GovernanceKernel governanceKernel;
    private final RentRequestDetector rentRequestDetector;
    private final RentDecisionWorkflow workflow;
    private final EphemeralMemory ephemeralMemory;   // 瞬时记忆：30 分钟会话上下文（memory=ephemeral 场景启用）
    private final ScenePolicyConfig scenePolicyConfig; // 查场景记忆策略（GROUP=none 不记）
    private final ShortTermMemory shortTermMemory;    // 短期记忆：对话永久落 MySQL
    private final ChatHistoryService chatHistoryService;  // 会话归属校验 + 新会话 id
    private final ConversationDao conversationDao;        // 会话上下文加载
    private final DailySummaryService dailySummaryService;  // 每日压缩：跨天对话前惰性补压
    private final SensitiveToolGuard sensitiveToolGuard;    // 敏感工具意图守卫（简历等需用户明确要求）
    private final UsageStatsService usageStats;             // 调用统计埋点（管理端"调用统计"Tab，可空）
    private final PersonaManager personaManager;            // 人设：自定义文本 → system prompt 注入（11-05，可空）
    private final UserRepository userRepository;            // 用户累计 token 用量落库（Web 端展示，可空=手动链路不记）

    public ChatService(@Qualifier("deepSeekLlmClient")LlmClient llmClient, ModelRouter modelRouter, SkillRegistry skillRegistry, GovernanceKernel governanceKernel,
                       RentRequestDetector rentRequestDetector, RentDecisionWorkflow workflow,
                       EphemeralMemory ephemeralMemory, ScenePolicyConfig scenePolicyConfig,
                       ShortTermMemory shortTermMemory, DailySummaryService dailySummaryService,
                       SensitiveToolGuard sensitiveToolGuard,
                       ChatHistoryService chatHistoryService, ConversationDao conversationDao,
                       UsageStatsService usageStats, PersonaManager personaManager, UserRepository userRepository) {
        this.llmClient = llmClient;
        this.modelRouter = modelRouter;
        this.skillRegistry = skillRegistry;
        this.governanceKernel = governanceKernel;
        this.rentRequestDetector = rentRequestDetector;
        this.workflow = workflow;
        this.ephemeralMemory = ephemeralMemory;
        this.scenePolicyConfig = scenePolicyConfig;
        this.shortTermMemory = shortTermMemory;
        this.dailySummaryService = dailySummaryService;
        this.sensitiveToolGuard = sensitiveToolGuard;
        this.chatHistoryService = chatHistoryService;
        this.conversationDao = conversationDao;
        this.usageStats = usageStats;
        this.personaManager = personaManager;
        this.userRepository = userRepository;
    }

    // 人设注入：用户设置了自定义人设 → 消息开头插一条 system prompt（未设置零影响）
    private void applyPersona(List<ChatMessage> messages, String userId) {
        if (personaManager == null || userId == null) {
            return;
        }
        String prompt = personaManager.systemPromptOf(userId);
        if (prompt != null) {
            messages.add(ChatMessage.fromSystem(prompt));
        }
    }

    public String chat(String userMessage, String userId, String scene) {
        return chat(userMessage, userId, scene, null);
    }

    // sessionId：由调用方（如 ask 端点 resolveSessionId）解析后传入，落库用同一个会话——
    // 否则"返回的 session"与"实际落库的 session"各算一套会脱节（11-05 小程序连续对话
    // 分成两个记录的真 bug）；null = 微信链路，内部 resolveSession（30 分钟窗口）
    public String chat(String userMessage, String userId, String scene, String sessionId) {
        logger.info("开始对话: {}", userMessage);

        // 双模式路由：完整租房需求 → 直跑工作流（跳过 LLM）；否则走自由问答工具循环
        RentRequest req = rentRequestDetector.parse(userMessage);
        if (req != null) {
            logger.info("检测到租房需求，进入工作流模式: {}", req);
            if (usageStats != null) usageStats.recordWorkflow(req.city());
            return workflow.execute(userId, "", req.city(), req.maxPrice());
        }

        // 高危操作确认闭环：用户回"确认" → 把待确认工具写入确认态（用完即弃），
        // 模型下一轮重试工具调用时治理内核关3放行；无待确认项时零副作用
        if (userMessage.trim().startsWith("确认")) {
            String pending = governanceKernel.confirm(userId);
            if (pending != null) {
                logger.info("用户确认高危操作: {}", pending);
            }
        }

        governanceKernel.resetRounds(userId);
        if (usageStats != null) usageStats.recordMessage(scene);
        // 惰性补压：跨天对话前查该用户摘要缺口，有就当场压（无缺口秒返回，不阻塞）
        if (dailySummaryService != null) {
            dailySummaryService.compressMissingForUser(userId);
        }
        List<ChatMessage> messages = new ArrayList<>();
        applyPersona(messages, userId);   // 人设注入（未设置不插，默认助手）
        // 瞬时记忆：该场景要记的话，先把历史对话拼在最前面（AI 才记得"之前聊过啥"）
        if (memoryEnabled(scene)) {
            for (ChatEntry entry : ephemeralMemory.load(userId)) {
                messages.add(new ChatMessage(entry.role(), entry.content()));
            }
        }
        messages.add(new ChatMessage("user", userMessage));
        List<ToolSpec> tools = skillRegistry.getToolSpecs();

        for (int round = 0; round < 20; round++) {
            ChatResponse resp = llmClient.chat(messages, tools);
            recordTokens(userId, llmClient.model(), inputChars(messages), resp.content().length());

            if (resp.toolCalls().isEmpty()) {
                logger.info("对话结束，共{}轮", round + 1);
                // 记忆双写：瞬时(Redis 30分钟) + 短期(MySQL 永久落库，带会话归属)
                if (memoryEnabled(scene)) {
                    ephemeralMemory.append(userId, userMessage, resp.content());
                    if (shortTermMemory != null) {
                        String sid = sessionId != null ? sessionId : resolveSession(userId);
                        shortTermMemory.saveAsync(userId, "user", userMessage, sid);
                        shortTermMemory.saveAsync(userId, "assistant", resp.content(), sid);
                    }
                }
                return resp.content();
            }

            messages.add(ChatMessage.fromAssistant(resp.toolCalls()));

            for (ToolCall call : resp.toolCalls()) {
                String result = executeTool(call.functionName(), call.arguments(), userId, scene, userMessage);
                messages.add(ChatMessage.fromToolResult(call.id(), result));
            }
        }
        logger.warn("对话超出工具循环上限20轮");
        return "工具调用次数过多，请换个问法";
    }

    // 执行一个工具：先过治理内核安检，放行才真正执行
    private String executeTool(String toolName, String args, String userId, String scene, String userMessage) {
        // 敏感工具意图守卫：读简历等私人信息工具，用户本轮消息必须含明确意图关键词
        // （实测事故：用户只说"你好"，模型自作主张调 mcp_liepin_resume 把简历全量展示）
        if (sensitiveToolGuard != null && sensitiveToolGuard.isSensitive(toolName)
                && !sensitiveToolGuard.hasIntent(toolName, userMessage)) {
            logger.info("敏感工具[{}] 被意图守卫拦截（用户未明确要求）", toolName);
            return "我没有看到你明确要求查看简历。如需查看，请直接说「查看我的简历」「帮我完善简历」等。";
        }
        CheckResult check = governanceKernel.check(toolName, userId, args, scene);
        if (!check.allowed()) {
            logger.info("工具[{}] 被治理内核拒绝: {}", toolName, check.reason());
            return check.reason();
        }
        Skill skill = skillRegistry.getSkill(toolName);
        if (skill == null) {
            return "没有这个技能: " + toolName;
        }
        if (usageStats != null) usageStats.recordToolCall(toolName);   // 统计：技能调用次数
        SkillResult result = skill.execute(new SkillContext(userId, args, ""));
        logger.info("工具[{}] 执行: 参数{} 结果{}",
                toolName, args, result.success() ? "成功" : "失败: " + result.content());
        return result.content();
    }

    // 统计辅助：本轮发给模型的全部输入字符数（估算 token 用，粗略即可）
    private long inputChars(List<ChatMessage> messages) {
        long sum = 0;
        for (ChatMessage m : messages) {
            if (m.content() != null) sum += m.content().length();
        }
        return sum;
    }

    // 本轮 token 双记：管理端全局统计（usageStats）+ 用户端累计落库（userRepository）
    // 估算公式共用 UsageStatsService.estimateTokens（单一来源，管理端/用户端口径一致）
    private void recordTokens(String userId, String model, long inputChars, long outputChars) {
        long est = UsageStatsService.estimateTokens(inputChars, outputChars);
        if (usageStats != null) {
            usageStats.recordTokens(model, inputChars, outputChars);
        }
        if (userRepository != null && userId != null) {
            try {
                userRepository.addTokens(userId, est);
            } catch (Exception e) {
                logger.warn("用户 token 用量落库失败: {}", e.getMessage());
            }
        }
    }

    // 该场景要不要记瞬时记忆：查 ScenePolicy 的 memory 列（ephemeral=记，none=不记如群聊）
    private boolean memoryEnabled(String scene) {
        if (ephemeralMemory == null || scenePolicyConfig == null) return false;
        ScenePolicy policy = scenePolicyConfig.getPolicies().get(Scene.valueOf(scene));
        return policy != null && "ephemeral".equals(policy.memory());
    }

    // 会话归属解析（四级优先级）：
    // ① userId 为空（未登录）→ null（不落库）
    // ② forceNew（前端「新对话」按钮）→ 强制新建会话
    // ③ requested 有效且属于该用户（点历史继续聊/页面内连续对话）→ 沿用
    // ④ 30 分钟滑动窗口兜底：最近一条消息在 30 分钟内 → 复用其会话并重新计时；超时/无记录才新建
    public String resolveSessionId(String userId, String requested, boolean forceNew) {
        if (userId == null) {
            return null;
        }
        if (forceNew) {
            return ChatHistoryService.newSessionId();
        }
        if (requested != null && !requested.isBlank() && chatHistoryService.owns(userId, requested)) {
            return requested;
        }
        String recent = recentSessionWithinWindow(userId);
        if (recent != null) {
            return recent;
        }
        return ChatHistoryService.newSessionId();
    }

    // SSE 打字机：按场景路由模型（创作→qwen-max，其余→deepseek-v4-flash），无工具调用
    // userId 非空时对话结束落记忆（瞬时 Redis + 短期 MySQL），Web SSE 才进历史
    public void stream(String userMessage, String scene, Consumer<String> onToken) {
        stream(userMessage, null, null, scene, onToken);
    }

    // sessionId：由 resolveSessionId 解析后传入（点历史=复用旧会话，新对话=新 id）
    public void stream(String userMessage, String userId, String sessionId, String scene, Consumer<String> onToken) {
        LlmClient client = modelRouter.clientFor(Scene.valueOf(scene));
        logger.info("流式对话[{}] 路由模型: {}", scene, client.model());

        List<ChatMessage> messages = new ArrayList<>();
        applyPersona(messages, userId);   // 人设注入（未设置不插，默认助手）
        if (userId != null && sessionId != null) {
            // 取该会话最近的上下文（最多 20 条，AI 记得之前聊过啥）
            List<ConversationDo> history = conversationDao.findBySession(userId, sessionId);
            int from = Math.max(0, history.size() - 20);
            for (int i = from; i < history.size(); i++) {
                messages.add(new ChatMessage(history.get(i).getRole(), history.get(i).getContent()));
            }
        }
        messages.add(new ChatMessage("user", userMessage));

        StringBuilder full = new StringBuilder();   // 攒完整回复，结束落记忆用
        client.stream(messages, List.of(), token -> {
            full.append(token);
            onToken.accept(token);
        });
        if (usageStats != null) {
            usageStats.recordMessage(scene);
        }
        recordTokens(userId, client.model(), inputChars(messages), full.length());
        // 落记忆（与 chat() 同策略：memory=ephemeral 场景才记；失败只记日志）
        if (userId != null && memoryEnabled(scene) && shortTermMemory != null) {
            try {
                ephemeralMemory.append(userId, userMessage, full.toString());
            } catch (Exception e) {
                logger.warn("瞬时记忆写入失败: {}", e.getMessage());
            }
            shortTermMemory.saveAsync(userId, "user", userMessage, sessionId);
            shortTermMemory.saveAsync(userId, "assistant", full.toString(), sessionId);
        }
    }

    // 微信链路会话归属：沿用最近一条消息的会话（30 分钟内），超时/无记录则开新会话
    private String resolveSession(String userId) {
        String recent = recentSessionWithinWindow(userId);
        if (recent != null) {
            return recent;
        }
        return ChatHistoryService.newSessionId();
    }

    // 30 分钟滑动窗口：查该用户最近一条消息，距现在 ≤30 分钟 → 复用其会话 id（重新计时）；否则 null
    // 微信 chat() 与 Web 端 resolveSessionId 共用同一份规则
    private String recentSessionWithinWindow(String userId) {
        if (shortTermMemory == null) {
            return null;
        }
        try {
            List<ConversationDo> recent = shortTermMemory.findHistory(userId, 1);
            if (recent != null && !recent.isEmpty() && recent.get(0).getSessionId() != null) {
                ConversationDo last = recent.get(0);
                if (last.getCreatedAt() != null
                        && Duration.between(last.getCreatedAt(), java.time.LocalDateTime.now())
                        .compareTo(Duration.ofMinutes(30)) <= 0) {
                    return last.getSessionId();
                }
            }
        } catch (Exception e) {
            logger.warn("解析会话归属失败，开新会话: {}", e.getMessage());
        }
        return null;
    }

    // ==================== SSE 流式 + 工具循环合流 ====================
    // 与 chat() 相同的工具循环语义，但模型请求走流式：
    //   正文 → 逐 token 回调 onToken（前端打字机）
    //   工具订单 → 静默执行（复用 executeTool 的安检），执行前回调 onToolStatus（前端"正在调用xxx"）
    //   无工具场景（如 CREATION 纯对话）→ 退回场景路由纯流式（保持 qwen-max 创作路由）
    public void streamWithTools(String userMessage, String userId, String sessionId, String scene,
                                Consumer<String> onToken, Consumer<String> onToolStatus) {
        // 双模式路由：完整租房需求 → 直跑工作流（报告整体推给前端，不经 LLM）
        RentRequest req = rentRequestDetector.parse(userMessage);
        if (req != null) {
            logger.info("流式对话检测到租房需求，进入工作流模式: {}", req);
            if (usageStats != null) usageStats.recordWorkflow(req.city());
            if (onToken != null) {
                onToken.accept(workflow.execute(userId, "", req.city(), req.maxPrice()));
            }
            return;
        }

        // 无工具场景（场景策略工具白名单为空，如 CREATION 创作对话）→ 场景路由纯流式
        ScenePolicy policy = scenePolicyConfig == null ? null
                : scenePolicyConfig.getPolicies().get(Scene.valueOf(scene));
        if (policy == null || policy.tools().isEmpty()) {
            stream(userMessage, userId, sessionId, scene, onToken);
            return;
        }

        // 高危确认闭环（同 chat()）：用户回"确认" → 写入确认态，模型下一轮重试放行
        if (userMessage.trim().startsWith("确认")) {
            governanceKernel.confirm(userId);
        }

        governanceKernel.resetRounds(userId);
        if (usageStats != null) usageStats.recordMessage(scene);
        if (dailySummaryService != null) {
            dailySummaryService.compressMissingForUser(userId);
        }

        List<ChatMessage> messages = new ArrayList<>();
        applyPersona(messages, userId);   // 人设注入（未设置不插，默认助手）
        if (userId != null && sessionId != null && conversationDao != null) {
            // 取该会话最近的上下文（最多 20 条，AI 记得之前聊过啥）
            List<ConversationDo> history = conversationDao.findBySession(userId, sessionId);
            int from = Math.max(0, history.size() - 20);
            for (int i = from; i < history.size(); i++) {
                messages.add(new ChatMessage(history.get(i).getRole(), history.get(i).getContent()));
            }
        }
        messages.add(new ChatMessage("user", userMessage));
        List<ToolSpec> tools = skillRegistry.getToolSpecs();

        StringBuilder full = new StringBuilder();
        boolean overLimit = false;
        for (int round = 0; round < 20; round++) {
            ChatResponse resp = llmClient.streamWithTools(messages, tools, token -> {
                full.append(token);
                onToken.accept(token);
            });
            recordTokens(userId, llmClient.model(), inputChars(messages), resp.content().length());
            if (resp.toolCalls().isEmpty()) {
                logger.info("流式对话结束，共{}轮", round + 1);
                break;
            }
            messages.add(ChatMessage.fromAssistant(resp.toolCalls()));
            for (ToolCall call : resp.toolCalls()) {
                if (onToolStatus != null) {
                    onToolStatus.accept(call.functionName());   // 前端状态事件：正在调用哪个工具
                }
                String result = executeTool(call.functionName(), call.arguments(), userId, scene, userMessage);
                messages.add(ChatMessage.fromToolResult(call.id(), result));
            }
            if (round == 19) {
                overLimit = true;
            }
        }
        if (overLimit) {
            logger.warn("流式对话超出工具循环上限20轮");
            onToken.accept("工具调用次数过多，请换个问法");
        }

        // 落记忆（与 stream() 同策略：memory=ephemeral 场景才记；失败只记日志）
        if (userId != null && memoryEnabled(scene) && shortTermMemory != null) {
            try {
                ephemeralMemory.append(userId, userMessage, full.toString());
            } catch (Exception e) {
                logger.warn("瞬时记忆写入失败: {}", e.getMessage());
            }
            shortTermMemory.saveAsync(userId, "user", userMessage, sessionId);
            shortTermMemory.saveAsync(userId, "assistant", full.toString(), sessionId);
        }
    }
}