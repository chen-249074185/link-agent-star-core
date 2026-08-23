package com.star.agent.memory;

import com.star.agent.llm.ChatMessage;
import com.star.agent.llm.ChatResponse;
import com.star.agent.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

// 每日压缩：把"昨天的对话流水"交给 LLM 提炼成摘要，存 daily_summary
// 三个触发入口都调 compressMissing()（查缺口补压）：
//   ① 启动时（ApplicationRunner）② 定时 00:30（@Scheduled）③ 跨天对话时（惰性）
@Component
public class DailySummaryService {

    private static final Logger logger = LoggerFactory.getLogger(DailySummaryService.class);

    private final ConversationMapper conversationMapper;
    private final DailySummaryMapper summaryMapper;
    private final LlmClient llmClient;

    public DailySummaryService(ConversationMapper conversationMapper,
                               DailySummaryMapper summaryMapper,
                               @Qualifier("deepSeekLlmClient") LlmClient llmClient) {
        this.conversationMapper = conversationMapper;
        this.summaryMapper = summaryMapper;
        this.llmClient = llmClient;
    }

    // 补压所有用户的缺口：最后一次摘要日期之后、今天之前的天数，逐天压
    public void compressMissing(List<String> userIds) {
        LocalDate today = LocalDate.now();
        for (String userId : userIds) {
            LocalDate lastDate = summaryMapper.findLatestDate(userId);
            LocalDate start = (lastDate == null) ? today.minusDays(7) : lastDate.plusDays(1);
            // 从缺口第一天压到"昨天"（今天还没过完，不压）
            LocalDate end = today.minusDays(1);
            if (start.isAfter(end)) {
                continue;   // 没缺口（昨天已压过），跳过
            }
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                compressDay(userId, d);
            }
        }
    }

    // 惰性触发：单个用户对话前调用（无缺口秒返回，有缺口当场补）
    public void compressMissingForUser(String userId) {
        compressMissing(List.of(userId));
    }

    // 压一天：取流水 → LLM 提炼（含闲聊遗忘过滤）→ 存摘要；任何一步失败只记日志不崩
    void compressDay(String userId, LocalDate date) {
        try {
            List<Conversation> day = conversationMapper.findByUserIdAndDate(userId, date);
            if (day.isEmpty()) {
                logger.info("{} 当天无对话，跳过压缩", date);
                return;
            }
            String summary = summarize(day);
            if (summary == null || summary.isBlank()) {
                logger.warn("{} 压缩摘要为空，跳过", date);
                return;
            }
            DailySummary s = new DailySummary();
            s.setUserId(userId);
            s.setSummaryDate(date);
            s.setContent(summary);
            summaryMapper.insert(s);
            logger.info("已压缩 {} 的 {} 条对话 → 摘要", date, day.size());
        } catch (Exception e) {
            logger.warn("压缩 {} 失败: {}", date, e.getMessage());
        }
    }

    // 把一天的流水交给 LLM：只留关键决定/偏好/待办/事实，寒暄闲聊全忘（无效闲聊遗忘规则）
    String summarize(List<Conversation> day) {
        StringBuilder sb = new StringBuilder();
        for (Conversation c : day) {
            sb.append(c.getRole()).append(": ").append(c.getContent()).append("\n");
        }
        List<ChatMessage> messages = List.of(
                new ChatMessage("system",
                        "你是对话摘要器。把以下一天的对话提炼成不超过5条要点。\n" +
                        "只保留：关键决定、用户偏好、待办事项、重要事实。\n" +
                        "遗忘规则：'你好''哈哈''好的''谢谢'等寒暄闲聊一律不记。\n" +
                        "输出格式：每条一行，用 '-' 开头。"),
                new ChatMessage("user", sb.toString())
        );
        ChatResponse resp = llmClient.chat(messages, List.of());
        return resp.content();
    }
}