package com.star.agent.governance;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

// 敏感工具意图守卫：私人信息类工具（读简历等）必须用户消息含明确意图关键词才放行
// 防"你好"等寒暄被模型自作主张调用敏感工具（实测事故：用户说你好，模型调 mcp_liepin_resume 把简历全量展示）
// 确定性关键词规则（不依赖 LLM 自觉），无意图时拒绝且不泄露任何内容
@Component
public class SensitiveToolGuard {

    // 敏感工具 → 意图关键词（用户本轮消息含任一关键词才放行）
    private final Map<String, List<String>> intents = Map.of(
            "mcp_liepin_resume", List.of("简历", "求职", "面试", "画像", "自我评价", "项目经历", "工作经历", "我的资料", "期望薪资"),
            // 接入自检也不许模型在寒暄时主动做（实测：用户说"你好"模型调 doctor 发一大段自检）
            "mcp_liepin_doctor", List.of("自检", "诊断", "检查猎聘", "接入检查", "接入是否正常", "猎聘能用吗")
    );

    // 该工具是否敏感（需要意图确认）
    public boolean isSensitive(String toolName) {
        return intents.containsKey(toolName);
    }

    // 用户消息是否含明确意图（任一关键词命中）
    public boolean hasIntent(String toolName, String userMessage) {
        if (userMessage == null) {
            return false;
        }
        return intents.getOrDefault(toolName, List.of()).stream()
                .anyMatch(userMessage::contains);
    }
}