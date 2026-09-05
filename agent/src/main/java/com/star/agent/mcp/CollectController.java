package com.star.agent.mcp;

import com.star.agent.infra.web.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 房源采集/索引接口：
//   POST /api/collect  = 爬链家 → 写 rag_data/houses/{城市}.txt → 全量重建索引（管理员/初始化用）
//   POST /api/reindex  = 只从全部数据文件重建索引（用户手动编辑 txt 后调用，不重新爬）
// 真实模式才有意义；mock 模式（MCP 未连接）返回降级提示
@Tag(name = "房源采集", description = "爬链家→写数据文件→重建索引（管理员/初始化用）")
@RestController
@RequestMapping("/api")
public class CollectController {

    private final HouseCollectorService collector;

    public CollectController(HouseCollectorService collector) {
        this.collector = collector;
    }

    @Operation(summary = "采集某城市房源", description = "爬链家 → 写 rag_data/houses/{城市}.txt → 全量重建索引")
    @PostMapping("/collect")
    public Result<HouseCollectorService.CollectResult> collect(
            @RequestParam("userId") String userId,
            @RequestParam("city") String city,
            @RequestParam("url") String url) {
        HouseCollectorService.CollectResult r = collector.collect(userId, city, url);
        return r.count() > 0 ? Result.ok(r) : Result.fail(500, r.message());
    }

    // 重建索引（全部城市）：用户编辑过 rag_data/houses/*.txt 后调用
    @Operation(summary = "全量重建索引", description = "读全部数据文件重建（不重新爬）")
    @PostMapping("/reindex")
    public Result<String> reindex(@RequestParam("userId") String userId) {
        return Result.ok(collector.reindexAll(userId));
    }
}