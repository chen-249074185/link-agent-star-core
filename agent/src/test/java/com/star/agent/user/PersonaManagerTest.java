package com.star.agent.user;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// PersonaManager 单测：预设列表 / 模板注入 / 未设置空 / 超长拒绝（存储层 mock）
class PersonaManagerTest {

    private final UserRepository repo = mock(UserRepository.class);
    private final PersonaManager manager = new PersonaManager(repo);

    @Test
    void presets_returnsFourQuickItems() {
        var presets = manager.presets();
        assertEquals(4, presets.size());
        assertTrue(presets.stream().anyMatch(p -> "租房专家".equals(p.get("name"))));
        assertTrue(presets.stream().anyMatch(p -> "求职顾问".equals(p.get("name"))));
        // 预设列表不含 prompt 细节（只给前端 id/name）
        assertFalse(presets.stream().anyMatch(p -> p.containsKey("prompt")));
    }

    // 设置了人设 → 模板注入（参考夏令营模板写法：%s = 自定义文本）
    @Test
    void systemPromptOf_wrapsCustomPersonaInTemplate() {
        when(repo.findPersona("u1")).thenReturn("你是东北雨姐，说话豪爽");
        String prompt = manager.systemPromptOf("u1");
        assertNotNull(prompt);
        assertTrue(prompt.contains("东北雨姐"));
        assertTrue(prompt.contains("完全以上述角色的身份说话"));
    }

    // 未设置 → null（ChatService 不插 system prompt，默认行为零变化）
    @Test
    void systemPromptOf_unsetReturnsNull() {
        when(repo.findPersona("u1")).thenReturn(null);
        assertNull(manager.systemPromptOf("u1"));
        when(repo.findPersona("u1")).thenReturn("   ");
        assertNull(manager.systemPromptOf("u1"), "纯空白视作未设置");
    }

    // 保存：trim 后入库；清除（null）也走同一 save
    @Test
    void save_trimsAndPersists() {
        assertTrue(manager.save("u1", "  你是猫娘，说话嗲嗲的  "));
        verify(repo).savePersona("u1", "你是猫娘，说话嗲嗲的");

        assertTrue(manager.save("u1", null));
        verify(repo).savePersona("u1", null);
    }

    // 超长拒绝（不落库）
    @Test
    void save_oversizedRejected() {
        String longText = "长".repeat(PersonaManager.MAX_PERSONA_LEN + 1);
        assertFalse(manager.save("u1", longText));
    }

    // 预设 prompt 查询：未知 id 返回 null
    @Test
    void presetPrompt_unknownIdReturnsNull() {
        assertNull(manager.presetPrompt("not-exist"));
        assertNotNull(manager.presetPrompt("creator"));
    }
}