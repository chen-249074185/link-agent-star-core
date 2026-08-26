package com.star.agent.mcp;

import com.star.agent.infra.web.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 房源采集入库接口：管理员/初始化时调用（爬链家 → 入指定用户知识库）
// 真实模式才有意义；mock 模式（MCP 未连接）返回降级提示
@RestController
@RequestMapping("/api")
public class CollectController {

    private final HouseCollectorService collector;

    public CollectController(HouseCollectorService collector) {
        this.collector = collector;
    }

    @PostMapping("/collect")
    public Result<HouseCollectorService.CollectResult> collect(
            @RequestParam("userId") String userId,
            @RequestParam("city") String city,
            @RequestParam("url") String url) {
        HouseCollectorService.CollectResult r = collector.collect(userId, city, url);
        return r.count() > 0 ? Result.ok(r) : Result.fail(500, r.message());
    }
}