package com.star.agent.infra.web;

import com.star.agent.skill.RiskLevel;
import com.star.agent.skill.Skill;
import com.star.agent.skill.SkillMeta;
import com.star.agent.skill.SkillRegistry;
import com.star.agent.skill.SkillType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// AdminController 四 Tab 端点测试（@WebMvcTest 不加载 AdminAuthFilter，凭证校验由 AdminAuthFilterTest 覆盖）
// 切片上下文无 McpConnection bean → Optional 为空 → RAG 端点走 503 降级
@WebMvcTest(AdminController.class)
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SkillRegistry skillRegistry;

    @MockBean
    private AdminSkillSwitch skillSwitch;

    @MockBean
    private UsageStatsService usageStats;

    @MockBean
    private LogTailService logTail;

    // 技能开关 Tab：清单带停用状态
    @Test
    void skills_returnsListWithDisabledFlag() throws Exception {
        Skill skill = new Skill() {
            @Override
            public SkillMeta meta() {
                return new SkillMeta("get_weather", "查天气", Map.of(),
                        RiskLevel.LOW, List.of("CHAT", "DATA"), "LOCAL", SkillType.EXT);
            }

            @Override
            public com.star.agent.skill.SkillResult execute(com.star.agent.skill.SkillContext ctx) {
                return null;
            }
        };
        when(skillRegistry.allSkills()).thenReturn(List.of(skill));
        when(skillSwitch.isDisabled("get_weather")).thenReturn(false);

        mockMvc.perform(get("/api/admin/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("get_weather"))
                .andExpect(jsonPath("$.data[0].skillType").value("EXT"))
                .andExpect(jsonPath("$.data[0].disabled").value(false));
    }

    // 技能开关 Tab：toggle 翻转状态
    @Test
    void toggleSkill_flipsDisabled() throws Exception {
        Skill skill = new Skill() {
            @Override
            public SkillMeta meta() {
                return new SkillMeta("get_weather", "查天气", Map.of(),
                        RiskLevel.LOW, List.of("CHAT"), "LOCAL", SkillType.EXT);
            }

            @Override
            public com.star.agent.skill.SkillResult execute(com.star.agent.skill.SkillContext ctx) {
                return null;
            }
        };
        when(skillRegistry.getSkill("get_weather")).thenReturn(skill);
        when(skillSwitch.isDisabled("get_weather")).thenReturn(false);

        mockMvc.perform(post("/api/admin/skills/get_weather/toggle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.disabled").value(true));

        verify(skillSwitch).setDisabled("get_weather", true);
    }

    // 技能开关 Tab：不存在的技能 404
    @Test
    void toggleSkill_unknownSkill_404() throws Exception {
        when(skillRegistry.getSkill("ghost")).thenReturn(null);

        mockMvc.perform(post("/api/admin/skills/ghost/toggle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    // 调用统计 Tab：快照透传
    @Test
    void stats_returnsSnapshot() throws Exception {
        when(usageStats.snapshot()).thenReturn(Map.of(
                "toolCalls", Map.of("get_weather", 3L),
                "totalTokens", 120L));

        mockMvc.perform(get("/api/admin/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolCalls.get_weather").value(3))
                .andExpect(jsonPath("$.data.totalTokens").value(120));
    }

    // 日志审计 Tab：关键词检索透传
    @Test
    void logs_returnsFilteredLines() throws Exception {
        when(logTail.tail("trace1", 200)).thenReturn(List.of("10:00:00 [main] [trace1] INFO 测试行"));

        mockMvc.perform(get("/api/admin/logs").param("q", "trace1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("10:00:00 [main] [trace1] INFO 测试行"));
    }

    // RAG 管理 Tab：切片无 McpConnection → 503 降级不崩
    @Test
    void ragKbs_noConnection_503() throws Exception {
        mockMvc.perform(get("/api/admin/rag/kbs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(503));
    }

    // RAG 管理 Tab：超管删除知识库 → 无连接 503（真实调用由握手测试/手工验证覆盖）
    @Test
    void ragDeleteKb_noConnection_503() throws Exception {
        mockMvc.perform(delete("/api/admin/rag/kb").param("userId", "u1").param("kbId", "u1-default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(503));
    }
}