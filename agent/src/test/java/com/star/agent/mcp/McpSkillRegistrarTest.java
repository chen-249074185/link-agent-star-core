package com.star.agent.mcp;

import com.star.agent.skill.RiskLevel;
import com.star.agent.skill.SkillRegistry;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

// McpSkillRegistrar 单测：listTools → 前缀注册进 SkillRegistry（mock 掉连接，握手由握手测试覆盖）
@ExtendWith(MockitoExtension.class)
class McpSkillRegistrarTest {

    @Mock
    private McpConnection connection;

    @Test
    void registerMcpSkills_registersAllPrefixed() {
        when(connection.serverName()).thenReturn("rag-mcp");
        when(connection.connect()).thenReturn(List.of(searchTool(), deleteTool()));
        SkillRegistry registry = new SkillRegistry(List.of());   // 空技能起步
        McpSkillRegistrar registrar = new McpSkillRegistrar(List.of(connection), registry);

        registrar.registerMcpSkills();

        assertNotNull(registry.getSkill("mcp_rag_search"));
        assertNotNull(registry.getSkill("mcp_rag_delete_kb"));
        assertEquals(RiskLevel.HIGH_RISK, registry.getSkill("mcp_rag_delete_kb").meta().riskLevel());
    }

    // 连接失败（空表）：不注册任何技能，不抛异常（R9 主进程不崩）
    @Test
    void registerMcpSkills_connectionDown_registersNothing() {
        when(connection.connect()).thenReturn(List.of());
        SkillRegistry registry = new SkillRegistry(List.of());
        McpSkillRegistrar registrar = new McpSkillRegistrar(List.of(connection), registry);

        registrar.registerMcpSkills();

        assertNull(registry.getSkill("mcp_rag_search"));
    }

    private McpSchema.Tool searchTool() {
        return McpSchema.Tool.builder()
                .name("rag_search")
                .description("检索知识库")
                .inputSchema(new McpSchema.JsonSchema("object",
                        Map.of("query", Map.of("type", "string")),
                        List.of("query"), null, null, null))
                .build();
    }

    private McpSchema.Tool deleteTool() {
        return McpSchema.Tool.builder()
                .name("rag_delete_kb")
                .description("删除知识库")
                .inputSchema(new McpSchema.JsonSchema("object",
                        Map.of("user_id", Map.of("type", "string"), "kb_id", Map.of("type", "string")),
                        List.of("user_id", "kb_id"), null, null, null))
                .build();
    }
}