package com.star.agent.mcp;

import com.star.agent.skill.RiskLevel;
import com.star.agent.skill.SkillContext;
import com.star.agent.skill.SkillMeta;
import com.star.agent.skill.SkillResult;
import com.star.agent.skill.SkillType;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// McpSkill 单测：MCP 工具→Skill 适配（元数据/入参过滤/身份注入/降级）
@ExtendWith(MockitoExtension.class)
class McpSkillTest {

    @Mock
    private McpConnection connection;

    private McpSkill skill;

    @BeforeEach
    void setUp() {
        skill = new McpSkill(searchTool(), connection);
    }

    // 元数据：前缀名 + 来源 + 分层打标 + 入参过滤 user_id/kb_id
    @Test
    void meta_prefixedAndFiltered() {
        SkillMeta meta = skill.meta();

        assertEquals("mcp_rag_search", meta.name());
        assertEquals("MCP:rag-mcp", meta.provider());
        assertEquals(SkillType.MAIN, meta.skillType());
        assertEquals(RiskLevel.LOW, meta.riskLevel());

        Map<String, Object> params = meta.parameters();
        Map<?, ?> properties = (Map<?, ?>) params.get("properties");   // 字段名在 properties 嵌套层
        assertFalse(properties.containsKey("user_id"), "user_id 不应暴露给 LLM");
        assertFalse(properties.containsKey("kb_id"), "kb_id 不应暴露给 LLM");
        assertTrue(properties.containsKey("query"));
        assertTrue(properties.containsKey("top_k"));
    }

    // 破坏性工具（delete）→ HIGH_RISK（治理内核需用户确认）
    @Test
    void meta_deleteToolIsHighRisk() {
        McpSkill deleteSkill = new McpSkill(deleteTool(), connection);
        assertEquals(RiskLevel.HIGH_RISK, deleteSkill.meta().riskLevel());
    }

    // 执行：LLM 参数 + 工单身份注入（user_id/kb_id 来自 ctx，防 LLM 乱填）
    @Test
    void execute_injectsIdentityAndCallsMcp() {
        when(connection.callTool(eq("rag_search"), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn("[score=0.1] 北京房源");

        SkillResult result = skill.execute(new SkillContext("u1", "{\"query\":\"北京\",\"top_k\":3}", "t1"));

        assertTrue(result.success());
        assertEquals("[score=0.1] 北京房源", result.content());

        verify(connection).callTool(eq("rag_search"),
                org.mockito.ArgumentMatchers.argThat(args ->
                        "u1".equals(args.get("user_id"))
                                && "u1-default".equals(args.get("kb_id"))
                                && "北京".equals(args.get("query"))
                                && args.get("top_k").equals(3)));   // 数值类型保真（不是字符串）
    }

    // 子进程失联：降级话术返回，不抛异常（R9 主进程不崩）
    @Test
    void execute_mcpDown_returnsGracefulMessage() {
        when(connection.callTool(eq("rag_search"), org.mockito.ArgumentMatchers.anyMap()))
                .thenThrow(new IllegalStateException("rag-mcp 未连接"));

        SkillResult result = skill.execute(new SkillContext("u1", "{\"query\":\"北京\"}", "t1"));

        assertFalse(result.success());
        assertTrue(result.content().contains("暂不可用"));
    }

    // 造一个 rag_search 工具的 MCP 说明（与 rag-mcp 服务端一致：含 user_id/kb_id/query/top_k）
    private McpSchema.Tool searchTool() {
        return McpSchema.Tool.builder()
                .name("rag_search")
                .description("在指定用户的私有知识库检索与问题最相关的文本片段")
                .inputSchema(new McpSchema.JsonSchema("object",
                        Map.of(
                                "user_id", Map.of("type", "string"),
                                "kb_id", Map.of("type", "string"),
                                "query", Map.of("type", "string", "description", "问题"),
                                "top_k", Map.of("type", "integer")
                        ),
                        List.of("user_id", "kb_id", "query"), null, null, null))
                .build();
    }

    // 造一个删库工具（破坏性 → 应标 HIGH_RISK）
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