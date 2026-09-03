package com.star.agent.user;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

// 人设管理器（11-05，参考夏令营 PersonaManager 的模板注入思路，不搬代码）：
// 用户自定义人设 = 自由文本，持久化在 users.persona 列（比夏令营内存 Map 强：重启不丢）
// 生效机制：ChatService 组消息时开头插一条 system prompt——
//   已设置 → "现在%s。\n\n【核心】完全以上述角色的身份说话…"（%s = 自定义文本）
//   未设置 → 不插（默认助手，行为零变化）
// 内置预设：快捷选择（点一下填入），自定义自由文本为主
@Component
public class PersonaManager {

    // 内置预设（快捷项）：id → {name, prompt}；用户可以选预设也可自由输入
    private static final Map<String, Map<String, String>> PRESETS = Map.of(
            "rent-expert", Map.of("name", "租房专家",
                    "prompt", "你是资深租房专家，精通城市片区分析、通勤评估、合同避坑与议价技巧。回答要数据化、给可执行建议。"),
            "career-advisor", Map.of("name", "求职顾问",
                    "prompt", "你是资深求职顾问，擅长简历优化、岗位分析、面试辅导与薪资谈判。回答要具体、可落地。"),
            "creator", Map.of("name", "创作伙伴",
                    "prompt", "你是充满创意的创作伙伴，擅长文案、故事、剧本与灵感发散。回答生动有感染力。"),
            "analyst", Map.of("name", "数据分析师",
                    "prompt", "你是严谨的数据分析师，擅长把问题拆成指标、用数据说话。回答结构化、有依据。")
    );

    // 人设注入模板（%s = 用户自定义文本；参考夏令营 PERSONA_SYSTEM_PROMPT_TEMPLATE 的写法）
    private static final String PERSONA_TEMPLATE = """
            现在%s。

            【核心】完全以上述角色的身份说话，语气、措辞、知识边界都符合该角色。
            不要自称 AI，不要说"作为一个人工智能"，直接以角色口吻交流。
            回答自然口语化，可以偶尔带一点角色特点，但不要过度表演影响实用性。""";

    // 人设文本长度上限（防超长 prompt 浪费 token）
    static final int MAX_PERSONA_LEN = 200;

    private final UserRepository userRepository;

    public PersonaManager(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    // 内置预设列表（给前端展示 id/name，不含 prompt）
    public List<Map<String, String>> presets() {
        return PRESETS.entrySet().stream()
                .map(e -> Map.of("id", e.getKey(), "name", e.getValue().get("name")))
                .toList();
    }

    // 取预设 prompt（未知 id 返回 null）
    public String presetPrompt(String id) {
        Map<String, String> p = PRESETS.get(id);
        return p == null ? null : p.get("prompt");
    }

    // 当前用户的人设文本（未设置返回 null）
    public String personaOf(String userId) {
        return userRepository.findPersona(userId);
    }

    // 设置/覆盖（null 或空 = 清除）；超长拒绝
    public boolean save(String userId, String persona) {
        if (persona != null && persona.trim().length() > MAX_PERSONA_LEN) {
            return false;
        }
        userRepository.savePersona(userId, persona == null || persona.isBlank() ? null : persona.trim());
        return true;
    }

    // 人设 system prompt：已设置 → 模板填充；未设置 → null（ChatService 不插，走默认）
    public String systemPromptOf(String userId) {
        String persona = personaOf(userId);
        if (persona == null || persona.isBlank()) {
            return null;
        }
        return PERSONA_TEMPLATE.formatted(persona);
    }
}