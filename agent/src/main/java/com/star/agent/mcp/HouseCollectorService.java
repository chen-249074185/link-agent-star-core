package com.star.agent.mcp;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// 房源采集入库服务（真实数据链路）：爬链家租房页 → 解析 → 写 rag_data/houses/{城市}.txt（数据源）→ 入库索引
// 数据源 = 纯文本文件（用户可点开查看/直接编辑，参照夏令营 rag_data 模式）；rag.db 只是从文件生成的检索索引
// 房源是通用公开数据（爬链家）→ 固定写入【共享房源库 kb-shared-houses】（11-03 方案1：全用户只读可检索，
// 归属 system）；岗位才是用户私有（liepin-mcp 用户 Token 查真实猎聘）——本服务只管房源
// 重建走 rag_admin_delete_kb（管理工具删共享库）+ rag_upload(user_id=system)（共享库仅 system 可写）
// mock 模式（无 MCP bean）→ Optional 空 → 调用方降级提示（R9 同款）
@Component
public class HouseCollectorService {

    // 与 rag-mcp SQLiteVecStore 常量一致：共享房源库 + 归属身份
    public static final String SHARED_HOUSES_KB = "kb-shared-houses";
    public static final String SHARED_OWNER = "system";

    private final Optional<McpConnection> crawlMcp;
    private final Optional<McpConnection> ragMcp;
    private final Path dataDir;   // 房源数据文件目录（缺省 rag_data/houses，测试可注入临时目录）

    @Autowired   // 多构造器时 Spring 靠它挑（坑15 同类）
    public HouseCollectorService(@Qualifier("crawlMcpConnection") Optional<McpConnection> crawlMcp,
                                 @Qualifier("ragMcpConnection") Optional<McpConnection> ragMcp) {
        this(crawlMcp, ragMcp, Paths.get("rag_data", "houses"));
    }

    HouseCollectorService(Optional<McpConnection> crawlMcp, Optional<McpConnection> ragMcp, Path dataDir) {
        this.crawlMcp = crawlMcp;
        this.ragMcp = ragMcp;
        this.dataDir = dataDir;
    }

    // 采集指定城市的链家租房列表：爬 → 解析 → 写数据文件 → 全量重建索引
    // 注意：索引必须全量重建（删一次旧库 + 全部城市文件一起上传），
    // 不能每城删库一次——否则采新城市会把已采集城市的索引删掉
    public CollectResult collect(String userId, String city, String url) {
        if (crawlMcp.isEmpty() || ragMcp.isEmpty()) {
            return new CollectResult(0, "真实 MCP 未连接（当前是 mock 模式，无法采集）");
        }
        String html = crawlMcp.get().callTool("crawl_fetch",
                Map.of("url", url, "mode", "static", "maxChars", 300000));
        List<LianjiaParser.House> houses = LianjiaParser.parse(html);
        if (houses.isEmpty()) {
            return new CollectResult(0, "未从页面解析到房源（页面结构变化或抓取失败）");
        }
        String content = buildText(city, houses);
        try {
            Files.createDirectories(dataDir);
            Files.writeString(dataDir.resolve(city + ".txt"), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new CollectResult(0, "写入数据文件失败: " + e.getMessage());
        }
        return new CollectResult(houses.size(), reindexAll(SHARED_OWNER));
    }

    // 从全部城市数据文件重建索引：读 rag_data/houses/*.txt → 清空共享房源库 → 逐个上传（system 身份）
    // （用户手动编辑任一城市文件后调用，全部城市一起重建，不重新爬）
    // 注意：共享库只读保护仅拦普通用户（userId≠system）；这里是管理员通道，走 system 身份写入
    public String reindexAll(String userId) {
        if (ragMcp.isEmpty()) {
            return "真实 MCP 未连接（当前是 mock 模式，无法重建索引）";
        }
        List<Path> files;
        try (var stream = Files.list(dataDir)) {
            files = stream.filter(p -> p.getFileName().toString().endsWith(".txt"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return "读取数据目录失败: " + e.getMessage();
        }
        if (files.isEmpty()) {
            return "数据目录没有房源文件: " + dataDir;
        }
        // 覆盖语义：先清共享库（rag_admin_delete_kb 是管理工具，可删共享库），再逐个上传（system 身份放行只读保护）
        ragMcp.get().callTool("rag_admin_delete_kb",
                Map.of("user_id", SHARED_OWNER, "kb_id", SHARED_HOUSES_KB));
        int uploaded = 0;
        for (Path file : files) {
            String content;
            try {
                content = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                continue;   // 单个文件读失败跳过，不中断其他城市
            }
            String city = file.getFileName().toString().replace(".txt", "");
            String ret = ragMcp.get().callTool("rag_upload", Map.of(
                    "user_id", SHARED_OWNER,
                    "kb_id", SHARED_HOUSES_KB,
                    "filename", city + "链家房源.txt",
                    "content", content));
            try {
                uploaded += Integer.parseInt(ret.replaceAll("\\D", ""));   // "已入库 N 块" → N
            } catch (NumberFormatException e) {
                // 返回格式不符只影响统计，不中断重建
            }
        }
        return "已重建索引：" + files.size() + " 个城市共 " + uploaded + " 块";
    }

    // 组装入库文本：每套一段用【空行】分隔（切片器按空行分段 → 每套房一块，检索粒度精确）
    // 标题清洗：去链家常见广告词（首次出租/免中介费等），格式对齐 HouseTextParser 可解析
    static String buildText(String city, List<LianjiaParser.House> houses) {
        StringBuilder sb = new StringBuilder();
        for (LianjiaParser.House h : houses) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(city).append(" ").append(clean(h.name()))
                    .append("，面积").append(String.format("%.0f", h.area())).append("平")
                    .append("，月租").append(h.price()).append("元");
        }
        return sb.toString();
    }

    // 链家标题广告词清洗（词表维护，够用即可）
    private static final String[] AD_WORDS = {
            "首次出租", "全新家具家电", "国企保障", "新店开业", "无中介费", "免中介费", "拎包入住",
            "随时看房", "押一付一", "0中介", "精装修", "近地铁", "带阳台"
    };

    static String clean(String name) {
        String s = name;
        for (String word : AD_WORDS) {
            s = s.replace(word, "");
        }
        return s.replaceAll("\\s+", " ").trim();
    }

    public record CollectResult(int count, String message) {
    }
}