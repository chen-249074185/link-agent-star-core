package com.star.agent.service;

import com.star.agent.governance.CheckResult;
import com.star.agent.governance.GovernanceKernel;
import com.star.agent.llm.ChatMessage;
import com.star.agent.llm.ChatResponse;
import com.star.agent.llm.LlmClient;
import com.star.agent.llm.ModelRouter;
import com.star.agent.llm.ToolCall;
import com.star.agent.llm.ToolSpec;
import com.star.agent.memory.ChatEntry;
import com.star.agent.memory.EphemeralMemory;
import com.star.agent.memory.ShortTermMemory;
import com.star.agent.scene.Scene;
import com.star.agent.scene.ScenePolicy;
import com.star.agent.scene.ScenePolicyConfig;
import com.star.agent.skill.Skill;
import com.star.agent.skill.SkillContext;
import com.star.agent.skill.SkillRegistry;
import com.star.agent.skill.SkillResult;
import com.star.agent.workflow.RentDecisionWorkflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

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

    public ChatService(@Qualifier("deepSeekLlmClient")LlmClient llmClient, ModelRouter modelRouter, SkillRegistry skillRegistry, GovernanceKernel governanceKernel,
                       RentRequestDetector rentRequestDetector, RentDecisionWorkflow workflow,
                       EphemeralMemory ephemeralMemory, ScenePolicyConfig scenePolicyConfig,
                       ShortTermMemory shortTermMemory) {
        this.llmClient = llmClient;
        this.modelRouter = modelRouter;
        this.skillRegistry = skillRegistry;
        this.governanceKernel = governanceKernel;
        this.rentRequestDetector = rentRequestDetector;
        this.workflow = workflow;
        this.ephemeralMemory = ephemeralMemory;
        this.scenePolicyConfig = scenePolicyConfig;
        this.shortTermMemory = shortTermMemory;
    }

    public String chat(String userMessage, String userId, String scene) {
        logger.info("开始对话: {}", userMessage);

        // 双模式路由：完整租房需求 → 直跑工作流（跳过 LLM）；否则走自由问答工具循环
        RentRequest req = rentRequestDetector.parse(userMessage);
        if (req != null) {
            logger.info("检测到租房需求，进入工作流模式: {}", req);
            return workflow.execute(userId, "", req.city(), req.maxPrice());
        }

        governanceKernel.resetRounds(userId);
        List<ChatMessage> messages = new ArrayList<>();
        // 瞬时记忆：该场景要记的话，先把历史对话拼在最前面（AI 才记得"之前聊过啥"）
        if (memoryEnabled(scene)) {
            for (ChatEntry entry : ephemeralMemory.load(userId)) {
                messages.add(new ChatMessage(entry.role(), entry.content()));
            }
        }
        messages.add(new ChatMessage("user", userMessage));
        List<ToolSpec> tools = skillRegistry.getToolSpecs();

        for (int round = 0; round < 5; round++) {
            ChatResponse resp = llmClient.chat(messages, tools);

            if (resp.toolCalls().isEmpty()) {
                logger.info("对话结束，共{}轮", round + 1);
                // 记忆双写：瞬时(Redis 30分钟) + 短期(MySQL 永久落库)
                if (memoryEnabled(scene)) {
                    ephemeralMemory.append(userId, userMessage, resp.content());
                    if (shortTermMemory != null) {
                        shortTermMemory.saveAsync(userId, "user", userMessage);
                        shortTermMemory.saveAsync(userId, "assistant", resp.content());
                    }
                }
                return resp.content();
            }

            messages.add(ChatMessage.fromAssistant(resp.toolCalls()));

            for (ToolCall call : resp.toolCalls()) {
                String result = executeTool(call.functionName(), call.arguments(), userId, scene);
                messages.add(ChatMessage.fromToolResult(call.id(), result));
            }
        }
        logger.warn("对话超出工具循环上限5轮");
        return "工具调用次数过多，请换个问法";
    }

    // 执行一个工具：先过治理内核安检，放行才真正执行
    private String executeTool(String toolName, String args, String userId, String scene) {
        CheckResult check = governanceKernel.check(toolName, userId, args, scene);
        if (!check.allowed()) {
            return check.reason();
        }
        Skill skill = skillRegistry.getSkill(toolName);
        if (skill == null) {
            return "没有这个技能: " + toolName;
        }
        SkillResult result = skill.execute(new SkillContext(userId, args, ""));
        return result.content();
    }

    // 该场景要不要记瞬时记忆：查 ScenePolicy 的 memory 列（ephemeral=记，none=不记如群聊）
    private boolean memoryEnabled(String scene) {
        if (ephemeralMemory == null || scenePolicyConfig == null) return false;
        ScenePolicy policy = scenePolicyConfig.getPolicies().get(Scene.valueOf(scene));
        return policy != null && "ephemeral".equals(policy.memory());
    }

    // SSE 打字机：按场景路由模型（创作→qwen-max，其余→deepseek-v4-flash），无工具调用
    public void stream(String userMessage, String scene, Consumer<String> onToken) {
        LlmClient client = modelRouter.clientFor(Scene.valueOf(scene));
        logger.info("流式对话[{}] 路由模型: {}", scene, client.model());
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("user", userMessage));
        client.stream(messages, List.of(), onToken);
    }
}