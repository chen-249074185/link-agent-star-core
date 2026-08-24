package com.star.crawl;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import tools.jackson.databind.json.JsonMapper;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

// Crawl MCP Server：抓取网页并结构化（crawl_fetch 工具，auto/json/static 三模式）
// 传输 = stdio（主进程以子进程方式拉起，JSON-RPC 对话）；EOF 感知退出（坑21 同款，零僵尸进程）
public class CrawlMcpServer {

    // static 模式返回原始 HTML 的上限（防超大页面撑爆工具结果）
    private static final int MAX_STATIC_CHARS = 50_000;

    public static void main(String[] args) {
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());
        EofAwareInputStream eofAware = new EofAwareInputStream(System.in);
        McpServerTransportProvider transport = new StdioServerTransportProvider(jsonMapper, eofAware, System.out);
        System.setOut(System.err);   // 之后日志全走 stderr，不污染协议流（MCP stdio 铁律）

        try {
            Fetcher fetcher = new Fetcher();
            ContentExtractor extractor = new ContentExtractor();
            JsonExtractor jsonExtractor = new JsonExtractor();

            McpServer.sync(transport)
                    .serverInfo("crawl-mcp-server", "0.1.0")
                    .tools(fetchTool(fetcher, extractor, jsonExtractor))
                    .build();
            System.err.println("crawl-mcp-server 就绪，等待客户端...");
        } catch (Exception e) {
            System.err.println("启动失败: " + e.getMessage());
            System.exit(1);
        }

        eofAware.awaitEof();
    }

    // 工具：crawl_fetch(url, mode=auto|json|static)
    // auto=正文密度提取；json=内嵌 JSON 结构化数据；static=原始 HTML（截断保护）
    private static SyncToolSpecification fetchTool(Fetcher fetcher, ContentExtractor extractor,
                                                   JsonExtractor jsonExtractor) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("crawl_fetch")
                .description("抓取指定 URL 并结构化返回：auto=提取正文 / json=提取内嵌JSON数据 / static=返回原始HTML")
                .inputSchema(new McpSchema.JsonSchema("object",
                        Map.of(
                                "url", Map.of("type", "string", "description", "要抓取的完整 URL"),
                                "mode", Map.of("type", "string", "description", "auto|json|static，缺省 auto")
                        ),
                        List.of("url"), null, null, null))
                .build();
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    Map<String, Object> args = request.arguments();
                    String url = String.valueOf(args.get("url"));
                    String mode = args.getOrDefault("mode", "auto") == null
                            ? "auto" : String.valueOf(args.get("mode")).toLowerCase();
                    try {
                        UrlGuard.check(url);   // SSRF 防护：内网地址先拦（抓取前最后一道闸）
                        String html = fetcher.fetch(url);
                        String result = switch (mode) {
                            case "json" -> {
                                String j = jsonExtractor.extractJson(html);
                                yield j == null ? "未找到内嵌 JSON" : j;
                            }
                            case "static" -> html.length() > MAX_STATIC_CHARS
                                    ? html.substring(0, MAX_STATIC_CHARS) + "\n...(截断)"
                                    : html;
                            default -> {
                                String main = extractor.extractMainText(html);
                                yield main.isEmpty() ? "未提取到正文（可能需换模式）" : main;
                            }
                        };
                        return McpSchema.CallToolResult.builder()
                                .content(List.of(new McpSchema.TextContent(result)))
                                .build();
                    } catch (Exception e) {
                        // 抓取失败降级话术返回，不抛（主进程不崩，R9 同思路）
                        return McpSchema.CallToolResult.builder()
                                .content(List.of(new McpSchema.TextContent("抓取失败: " + e.getMessage())))
                                .build();
                    }
                })
                .build();
    }

    // EOF 感知流（坑21 的解法）：客户端断开 → 主线程退出，不残留僵尸进程
    private static class EofAwareInputStream extends FilterInputStream {

        private final CountDownLatch eofLatch = new CountDownLatch(1);

        EofAwareInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b == -1) eofLatch.countDown();
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n == -1) eofLatch.countDown();
            return n;
        }

        void awaitEof() {
            try {
                eofLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}