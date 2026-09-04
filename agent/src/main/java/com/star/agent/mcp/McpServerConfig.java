package com.star.agent.mcp;

import com.star.agent.infra.config.AppConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.nio.file.Paths;
import java.util.Map;

// MCP server 装配：按 server 配置创建多个 McpConnection（rag-mcp / crawl-mcp / liepin-mcp / luckin-mcp）
// stdio 模式（本地 jar）与 http 模式（远程 Streamable HTTP，瑞幸/麦当劳官方 MCP）并存
// 仅 mock-enabled=false 时注册（true 时 Mock 占位技能在位，MCP 全部不拉起）
@Configuration
public class McpServerConfig {

    @Bean
    @Primary   // rag-mcp 是主知识库连接：RagController 的 Optional<McpConnection> 默认取它
    @ConditionalOnProperty(name = "linkagent.workflow.mock-enabled", havingValue = "false")
    public McpConnection ragMcpConnection(
            @Value("${linkagent.mcp.rag-jar:../rag-mcp/target/rag-mcp-0.0.1-SNAPSHOT.jar}") String jar,
            @Value("${linkagent.mcp.rag-data-dir:rag_data}") String dataDir,
            AppConfig config) {
        // 数据目录转绝对路径注入子进程：rag.db 落在稳定目录（不放 target，防 mvn clean 删数据）
        String absDataDir = Paths.get(dataDir).toAbsolutePath().normalize().toString();
        // AMAP_KEY 供 rag_internal_geocode 地理编码（地址→经纬度，带 geo_cache 缓存）
        return new McpConnection("rag-mcp", jar,
                Map.of("RAG_DATA_DIR", absDataDir, "AMAP_KEY", config.amapApiKey()));
    }

    @Bean
    @ConditionalOnProperty(name = "linkagent.workflow.mock-enabled", havingValue = "false")
    public McpConnection crawlMcpConnection(
            @Value("${linkagent.mcp.crawl-jar:../crawl-mcp/target/crawl-mcp-0.0.1-SNAPSHOT.jar}") String jar) {
        return new McpConnection("crawl-mcp", jar);
    }

    @Bean
    @ConditionalOnProperty(name = "linkagent.workflow.mock-enabled", havingValue = "false")
    public McpConnection liepinMcpConnection(
            @Value("${linkagent.mcp.liepin-jar:../liepin-mcp/target/liepin-mcp-0.0.1-SNAPSHOT.jar}") String jar,
            AppConfig config) {
        // 猎聘 Token 走子进程环境变量注入（零明文进命令行/日志）
        return new McpConnection("liepin-mcp", jar,
                Map.of("LIEPIN_USER_TOKEN", config.liepinUserToken()));
    }

    @Bean
    @ConditionalOnProperty(name = "linkagent.workflow.mock-enabled", havingValue = "false")
    public McpConnection luckinMcpConnection(AppConfig config) {
        // 瑞幸官方 MCP（远程 Streamable HTTP，Bearer Token 认证，Token 走 .env 零明文）
        // 预热工具选只读的 searchProductForMcp（查商品不花钱）；下单类 createOrder 走 HIGH_RISK 确认闭环
        return new McpConnection("luckin-mcp",
                "https://gwmcp.lkcoffee.com/order/user/mcp",
                config.luckinMcpToken(),
                "searchProductForMcp");
    }
}