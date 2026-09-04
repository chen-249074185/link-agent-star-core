package com.star.agent.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

class LuckinHandshakeTest {

    @Test
    void connect() {
        McpConnection c = new McpConnection("luckin-mcp",
                "https://gwmcp.lkcoffee.com/order/user/mcp",
                System.getenv().getOrDefault("LUCKIN_MCP_TOKEN", "1d33938ddc764cadb03e2d579debca639mcpLUCKIN_MCP_AI"),
                "searchProductForMcp");
        List<io.modelcontextprotocol.spec.McpSchema.Tool> tools = c.connect();
        System.out.println("工具数: " + tools.size());
        tools.forEach(t -> System.out.println(" - " + t.name() + ": " + t.description()));
        assertFalse(tools.isEmpty());
        c.close();
    }
}