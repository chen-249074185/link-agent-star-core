package com.star.agent.mcp;

import com.star.agent.skill.SkillRegistry;
import com.star.agent.user.UserConfigRepository;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

// MCP 技能注册器：逐个连接所有 MCP server（rag-mcp/crawl-mcp）→ listTools → 包成 McpSkill（前缀 mcp_）注册进 SkillRegistry
// 仅 mock-enabled=false 时启用（与 Mock 占位技能互斥，实现"翻转开关即切换真实 MCP"）
@Component
@ConditionalOnProperty(name = "linkagent.workflow.mock-enabled", havingValue = "false")
public class McpSkillRegistrar {

    private static final Logger logger = LoggerFactory.getLogger(McpSkillRegistrar.class);

    // 管理工具前缀：rag-mcp 的 rag_admin_* 系列（全库视角/超管删除），只给 /api/admin 直连，不注册成技能
    private static final String ADMIN_TOOL_PREFIX = "rag_admin_";
    // 内部工具前缀：rag_internal_*（如 rag_internal_geocode 地理编码缓存），只给技能/服务直连，不注册给 LLM
    private static final String INTERNAL_TOOL_PREFIX = "rag_internal_";

    private final List<McpConnection> connections;   // 全部 MCP server 连接（按 server 配置装配）
    private final SkillRegistry skillRegistry;
    private final UserConfigRepository userConfigRepository;   // 猎聘域注入每用户自己的 Token

    public McpSkillRegistrar(List<McpConnection> connections, SkillRegistry skillRegistry,
                             UserConfigRepository userConfigRepository) {
        this.connections = connections;
        this.skillRegistry = skillRegistry;
        this.userConfigRepository = userConfigRepository;
    }

    @PostConstruct
    public void registerMcpSkills() {
        for (McpConnection connection : connections) {
            List<McpSchema.Tool> tools = connection.connect();   // 握手失败返回空表（R9 不崩）
            for (McpSchema.Tool tool : tools) {
                // 管理工具（rag_admin_* 前缀）：超管直连调用，绝不注册给 LLM 当技能（防越权）
                if (tool.name().startsWith(ADMIN_TOOL_PREFIX)
                        || tool.name().startsWith(INTERNAL_TOOL_PREFIX)) {
                    logger.info("管理/内部工具 {} 不注册给 LLM（仅内部直连调用）", tool.name());
                    continue;
                }
                skillRegistry.register(new McpSkill(tool, connection, userConfigRepository));
                logger.info("MCP 技能注册: mcp_{} (来源 {})", tool.name(), connection.serverName());
            }
            if (tools.isEmpty()) {
                logger.warn("{} 无工具，未注册其 MCP 技能", connection.serverName());
            }
        }
    }
}