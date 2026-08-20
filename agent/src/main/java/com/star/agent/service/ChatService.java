package com.star.agent.service;

import com.star.agent.governance.CheckResult;
import com.star.agent.governance.GovernanceKernel;
import com.star.agent.llm.ChatMessage;
import com.star.agent.llm.ChatResponse;
import com.star.agent.llm.LlmClient;
import com.star.agent.llm.ToolCall;
import com.star.agent.llm.ToolSpec;
import com.star.agent.skill.Skill;
import com.star.agent.skill.SkillContext;
import com.star.agent.skill.SkillRegistry;
import com.star.agent.skill.SkillResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Component
public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    private final LlmClient llmClient;
    private final SkillRegistry skillRegistry;
    private final GovernanceKernel governanceKernel;

    public ChatService(LlmClient llmClient, SkillRegistry skillRegistry, GovernanceKernel governanceKernel) {
        this.llmClient = llmClient;
        this.skillRegistry = skillRegistry;
        this.governanceKernel = governanceKernel;
    }

    public String chat(String userMessage, String userId, String scene) {
        logger.info("开始对话: {}", userMessage);
        governanceKernel.resetRounds(userId);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("user", userMessage));
        List<ToolSpec> tools = skillRegistry.getToolSpecs();

        for (int round = 0; round < 5; round++) {
            ChatResponse resp = llmClient.chat(messages, tools);

            if (resp.toolCalls().isEmpty()) {
                logger.info("对话结束，共{}轮", round + 1);
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

    public void stream(String userMessage, Consumer<String> onToken) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("user", userMessage));
        llmClient.stream(messages, List.of(), onToken);
    }
}