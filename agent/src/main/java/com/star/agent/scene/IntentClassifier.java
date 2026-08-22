package com.star.agent.scene;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.star.agent.llm.ChatMessage;
import com.star.agent.llm.ChatResponse;
import com.star.agent.llm.LlmClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

// LLM 意图分类（三层识别的第②层，09-09 才接进 SceneDetector）
// 拿不准就回退 CHAT：置信度 <0.7 / 场景非法 / 调用异常，全部走兜底
@Component
public class IntentClassifier {

    // 点名用 DashScope 那个实现（@Qualifier = 同接口多实现时选哪个）
    private final LlmClient llmClient;
    private final ObjectMapper mapper = new ObjectMapper();

    // 置信度阈值：低于它模型自己都没把握，别硬猜
    private static final double CONFIDENCE_THRESHOLD = 0.7;

    public IntentClassifier(@Qualifier("dashScopeLlmClient") LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    // 入口：给最近 3 条消息，返回判定的场景（永远不抛异常，失败就 CHAT）
    public Scene classify(List<String> recentMessages) {
        try {
            ChatResponse resp = llmClient.chat(buildMessages(recentMessages), List.of());
            String json = resp.content().trim();
            // 解析模型吐的 JSON：{"scene":"DATA","confidence":0.92}
            JsonNode node = mapper.readTree(json);
            double confidence = node.path("confidence").asDouble(0);
            String sceneName = node.path("scene").asText();

            // 置信度不够 / 场景名不是合法枚举 → 回退闲聊
            if (confidence < CONFIDENCE_THRESHOLD) {
                return Scene.CHAT;
            }
            return Scene.valueOf(sceneName);
        } catch (Exception e) {
            // 模型挂了/JSON 坏了/枚举非法 → 不能崩，回退 CHAT
            return Scene.CHAT;
        }
    }

    // 组装消息：system 定义规则 + 最近 3 条用户消息（最后一条是最新）
    private List<ChatMessage> buildMessages(List<String> recentMessages) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.fromSystem(SYSTEM_PROMPT));
        for (String msg : recentMessages) {
            messages.add(ChatMessage.fromUser(msg));
        }
        return messages;
    }

    // 分类规则：场景定义 + 输出格式要求（配合 response_format=json_object 强制 JSON）
    private static final String SYSTEM_PROMPT = """
            你是场景分类器。根据对话历史判断最新一条消息属于哪个场景，只输出 JSON，不要多余文字。
            场景定义：
            - CHAT: 日常闲聊、问候、寒暄、情感表达
            - CREATION: 画图、写诗、编故事、作文等创作请求
            - OFFICE: 周报、日报、邮件、PPT、表格等办公任务
            - DATA: 查天气、股价、汇率、计算等数据查询
            - GROUP: 群聊场景（@全体成员 等）
            输出格式：{"scene":"CHAT","confidence":0.9}
            """;
}