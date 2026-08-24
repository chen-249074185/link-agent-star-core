package com.star.agent.mcp;

import com.star.agent.skill.SkillRegistry;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

// MCP 技能注册器：连接 rag-mcp → listTools → 逐个包成 McpSkill（前缀 mcp_）→ 注册进 SkillRegistry
// 仅 mock-enabled=false 时启用（与 Mock 占位技能互斥，实现"翻转开关即切换真实 MCP"）
@Component
@ConditionalOnProperty(name = "linkagent.workflow.mock-enabled", havingValue = "false")
public class McpSkillRegistrar {

    private static final Logger logger = LoggerFactory.getLogger(McpSkillRegistrar.class);

    private final McpConnection connection;
    private final SkillRegistry skillRegistry;

    public McpSkillRegistrar(McpConnection connection, SkillRegistry skillRegistry) {
        this.connection = connection;
        this.skillRegistry = skillRegistry;
    }

    @PostConstruct
    public void registerMcpSkills() {
        List<McpSchema.Tool> tools = connection.connect();   // 握手失败返回空表（R9 不崩）
        for (McpSchema.Tool tool : tools) {
            skillRegistry.register(new McpSkill(tool, connection));
            logger.info("MCP 技能注册: mcp_{}", tool.name());
        }
        if (tools.isEmpty()) {
            logger.warn("MCP 技能为空，未注册任何 MCP 技能");
        }
    }
}