package com.star.agent.mcp;

import com.star.agent.skill.RiskLevel;
import com.star.agent.skill.SkillContext;
import com.star.agent.skill.SkillMeta;
import com.star.agent.skill.SkillResult;
import com.star.agent.skill.SkillType;
import com.star.agent.user.UserConfigRepository;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        when(connection.serverName()).thenReturn("rag-mcp");
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

    // 越权防护（R10 实战回归，11-01 联调抓到）：LLM 参数里伪造 user_id/kb_id → 必须被忽略，
    // 保持 execute() 注入的 ctx.userId() 身份（否则模型可伪造他人身份访问私有库）
    @Test
    void execute_llmForgesIdentityParams_ignored() {
        when(connection.callTool(eq("rag_search"), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn("[score=0.1] 北京房源");

        // 模型输出里带伪造的 user_id=user_001、kb_id=default（schema 已过滤仍可能编造，如联调日志所见）
        SkillResult result = skill.execute(
                new SkillContext("u1", "{\"query\":\"北京\",\"top_k\":3,\"user_id\":\"user_001\",\"kb_id\":\"default\"}", "t1"));

        assertTrue(result.success());
        verify(connection).callTool(eq("rag_search"),
                org.mockito.ArgumentMatchers.argThat(args ->
                        "u1".equals(args.get("user_id"))                  // 保持注入身份，不是 user_001
                                && "u1-default".equals(args.get("kb_id")) // 保持默认库，不是 default
                                && "北京".equals(args.get("query"))
                                && args.get("top_k").equals(3)));
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

    // 猎聘域：未配置 Token → 明确拒绝并引导配置，技能不执行（用户私有数据，绝不回退用别人的）
    @Test
    void liepinTool_withoutUserToken_denied() {
        when(connection.serverName()).thenReturn("liepin-mcp");
        UserConfigRepository repo = mock(UserConfigRepository.class);
        when(repo.findLiepinToken("u1")).thenReturn(null);
        McpSkill liepin = new McpSkill(searchTool(), connection, repo);

        SkillResult result = liepin.execute(new SkillContext("u1", "{}", "t1"));

        assertFalse(result.success());
        assertTrue(result.content().contains("还没配置猎聘 Token"), "应引导配置");
        verify(connection, never()).callTool(anyString(), org.mockito.ArgumentMatchers.anyMap());
    }

    // 猎聘域：已配置 → 注入该用户自己的 Token（user_token 参数，LLM 不可见）
    @Test
    void liepinTool_withUserToken_injected() {
        when(connection.serverName()).thenReturn("liepin-mcp");
        when(connection.callTool(eq("rag_search"), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn("ok");
        UserConfigRepository repo = mock(UserConfigRepository.class);
        when(repo.findLiepinToken("u1")).thenReturn("userA-token");
        when(repo.findLiepinToken("u2")).thenReturn("userB-token");
        McpSkill liepin = new McpSkill(searchTool(), connection, repo);

        liepin.execute(new SkillContext("u1", "{}", "t1"));
        liepin.execute(new SkillContext("u2", "{}", "t2"));

        verify(connection).callTool(eq("rag_search"),
                org.mockito.ArgumentMatchers.argThat(args -> "userA-token".equals(args.get("user_token"))));
        verify(connection).callTool(eq("rag_search"),
                org.mockito.ArgumentMatchers.argThat(args -> "userB-token".equals(args.get("user_token"))));
    }

    // 猎聘工具参数过滤：user_token 不暴露给 LLM（防乱填他人 Token）
    @Test
    void liepinTool_metaHidesUserToken() {
        when(connection.serverName()).thenReturn("liepin-mcp");
        McpSkill liepin = new McpSkill(searchTool(), connection, null);

        Map<?, ?> properties = (Map<?, ?>) liepin.meta().parameters().get("properties");

        assertFalse(properties.containsKey("user_token"), "user_token 不应暴露给 LLM");
    }

    // 例外：liepin_doctor 是接入自检（无用户维度），不检查用户 Token（走全局环境变量）
    @Test
    void doctorTool_skipsUserTokenCheck() {
        when(connection.serverName()).thenReturn("liepin-mcp");
        when(connection.callTool(eq("liepin_doctor"), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn("CLI 可用，Token 有效");
        McpSchema.Tool doctor = McpSchema.Tool.builder()
                .name("liepin_doctor")
                .description("接入自检")
                .inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null))
                .build();
        UserConfigRepository repo = mock(UserConfigRepository.class);   // 无任何配置
        McpSkill skill = new McpSkill(doctor, connection, repo);

        SkillResult result = skill.execute(new SkillContext("u1", "{}", "t1"));

        assertTrue(result.success(), "doctor 不依赖用户 Token，应正常自检");
        verify(connection).callTool(eq("liepin_doctor"), org.mockito.ArgumentMatchers.anyMap());
    }
}