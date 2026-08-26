package com.star.agent.mcp;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

// 房源采集入库服务（真实数据链路）：爬链家租房页 → 解析房源 → 组装文本 → rag_upload 入用户知识库
// 房源是通用公开数据（爬链家），岗位才是用户私有（liepin-mcp 用户 Token 查真实猎聘）——本服务只管房源
// mock 模式（无 MCP bean）→ Optional 空 → 调用方降级提示（R9 同款）
@Component
public class HouseCollectorService {

    private final Optional<McpConnection> crawlMcp;
    private final Optional<McpConnection> ragMcp;

    public HouseCollectorService(@Qualifier("crawlMcpConnection") Optional<McpConnection> crawlMcp,
                                 @Qualifier("ragMcpConnection") Optional<McpConnection> ragMcp) {
        this.crawlMcp = crawlMcp;
        this.ragMcp = ragMcp;
    }

    // 采集指定城市的链家租房列表 → 入 userId 的默认知识库
    public CollectResult collect(String userId, String city, String url) {
        if (crawlMcp.isEmpty() || ragMcp.isEmpty()) {
            return new CollectResult(0, "真实 MCP 未连接（当前是 mock 模式，无法采集）");
        }
        String html = crawlMcp.get().callTool("crawl_fetch",
                Map.of("url", url, "mode", "static"));
        List<LianjiaParser.House> houses = LianjiaParser.parse(html);
        if (houses.isEmpty()) {
            return new CollectResult(0, "未从页面解析到房源（页面结构变化或抓取失败）");
        }
        String content = buildText(city, houses);
        String kbId = userId + "-default";
        String result = ragMcp.get().callTool("rag_upload", Map.of(
                "user_id", userId,
                "kb_id", kbId,
                "filename", city + "链家房源.txt",
                "content", content));
        return new CollectResult(houses.size(), result);
    }

    // 组装入库文本：每套一段（段落分隔 → 切片器按段切 → 每套房一块），格式对齐 HouseTextParser 可解析
    static String buildText(String city, List<LianjiaParser.House> houses) {
        StringBuilder sb = new StringBuilder();
        for (LianjiaParser.House h : houses) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(city).append(" ").append(h.name())
                    .append("，面积").append(String.format("%.0f", h.area())).append("平")
                    .append("，月租").append(h.price()).append("元");
        }
        return sb.toString();
    }

    public record CollectResult(int count, String message) {
    }
}