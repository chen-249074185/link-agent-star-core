package com.star.agent.mcp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

// MCP server 装配：按 server 配置创建多个 McpConnection（rag-mcp + crawl-mcp 各一子进程）
// 仅 mock-enabled=false 时注册（true 时 Mock 占位技能在位，MCP 全部不拉起）
@Configuration
public class McpServerConfig {

    @Bean
    @Primary   // rag-mcp 是主知识库连接：RagController 的 Optional<McpConnection> 默认取它
    @ConditionalOnProperty(name = "linkagent.workflow.mock-enabled", havingValue = "false")
    public McpConnection ragMcpConnection(
            @Value("${linkagent.mcp.rag-jar:../rag-mcp/target/rag-mcp-0.0.1-SNAPSHOT.jar}") String jar) {
        return new McpConnection("rag-mcp", jar);
    }

    @Bean
    @ConditionalOnProperty(name = "linkagent.workflow.mock-enabled", havingValue = "false")
    public McpConnection crawlMcpConnection(
            @Value("${linkagent.mcp.crawl-jar:../crawl-mcp/target/crawl-mcp-0.0.1-SNAPSHOT.jar}") String jar) {
        return new McpConnection("crawl-mcp", jar);
    }
}