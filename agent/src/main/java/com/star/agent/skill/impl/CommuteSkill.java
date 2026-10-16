package com.star.agent.skill.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.star.agent.infra.config.AppConfig;
import com.star.agent.mcp.McpConnection;
import com.star.agent.skill.JsonSchemaBuilder;
import com.star.agent.skill.RiskLevel;
import com.star.agent.skill.Skill;
import com.star.agent.skill.SkillContext;
import com.star.agent.skill.SkillMeta;
import com.star.agent.skill.SkillResult;
import com.star.agent.skill.SkillType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

// 高德路线规划真实接入：commute_estimate 技能（MAIN 主线：租房通勤成本测算）
// 地址入参 → 地理编码（优先 rag-mcp 的 geo_cache 缓存，避免重复调高德）→ 驾车路线
// 出发地必填：IP 定位实测不可靠（高德 v3/ip 对部分 IP 无数据，且只有城市级精度），
// 模型应先询问用户出发位置再测算（用户拍板方案 A）
// 输出：距离/用时/主要路段/可点击"查看路线"高德链接（callnative=1 唤起高德 App）
@Component
public class CommuteSkill implements Skill {

    private static final Logger logger = LoggerFactory.getLogger(CommuteSkill.class);

    private static final String DEFAULT_BASE_URL = "https://restapi.amap.com/v3/direction/driving";
    private static final String DEFAULT_GEOCODE_URL = "https://restapi.amap.com/v3/geocode/geo";
    private static final String COORD_PATTERN = "-?\\d+(\\.\\d+)?,-?\\d+(\\.\\d+)?";

    private final String baseUrl;
    private final String geocodeUrl;
    private final String apiKey;
    private final McpConnection ragConnection;   // null=无 rag 连接（mock 模式/手动链路）→ 直接调高德无缓存
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public CommuteSkill(AppConfig appConfig, ObjectProvider<McpConnection> ragConnectionProvider) {
        this(DEFAULT_BASE_URL, DEFAULT_GEOCODE_URL, appConfig.amapApiKey(),
                ragConnectionProvider == null ? null : ragConnectionProvider.getIfAvailable());
    }

    // 手动链路（纯 main 不进 Spring）：无 rag 连接，直接调高德
    public CommuteSkill(AppConfig appConfig) {
        this(DEFAULT_BASE_URL, DEFAULT_GEOCODE_URL, appConfig.amapApiKey(), null);
    }

    // 测试用：注入假服务器地址 + 任意 key，不打真实高德
    public CommuteSkill(String baseUrl, String apiKey) {
        this(baseUrl, DEFAULT_GEOCODE_URL, apiKey, null);
    }

    // 全参：测试可注入假 geocode 服务器 + rag 连接（验证缓存路径）
    public CommuteSkill(String baseUrl, String geocodeUrl, String apiKey, McpConnection ragConnection) {
        this.baseUrl = baseUrl;
        this.geocodeUrl = geocodeUrl;
        this.apiKey = apiKey;
        this.ragConnection = ragConnection;
    }

    @Override
    public SkillMeta meta() {
        return new SkillMeta(
                "commute_estimate",
                "测算两个地点之间的驾车通勤，出发地和目的地必填（支持地址或经纬度）；若用户没说出发地，先询问用户所在位置再调用。输出包含『打车：』与『查看路线：』两行链接——回复用户时必须原样完整保留这两行，不得省略或改写",
                JsonSchemaBuilder.object()
                        .addString("origin", "出发地：地址（如'杭州东站'）或经纬度（如'120.211,30.289'）", true)
                        .addString("destination", "目的地：地址（如'网易大厦'）或经纬度（如'120.216,30.189'）", true)
                        .build(),
                RiskLevel.LOW,
                List.of("CHAT", "DATA"),
                "LOCAL",
                SkillType.MAIN
        );
    }

    @Override
    public SkillResult execute(SkillContext ctx) {
        try {
            JsonNode node = mapper.readTree(ctx.args());
            String origin = node.path("origin").asText();
            String destination = node.path("destination").asText();
            if (origin.isEmpty()) {
                return new SkillResult(false, "请提供出发地（如：从'杭州东站'出发），我好帮你测算通勤");
            }
            if (destination.isEmpty()) {
                return new SkillResult(false, "请提供目的地（地址或经纬度）");
            }

            String originLoc = resolveLocation(origin);
            if (originLoc == null) {
                return new SkillResult(false, "未找到起点「" + origin + "」，请提供更具体的地址（如：杭州市西湖区）");
            }
            String destLoc = resolveLocation(destination);
            if (destLoc == null) {
                return new SkillResult(false, "未找到目的地「" + destination + "」，请提供更具体的地址（如：杭州市滨江区）");
            }

            String url = baseUrl + "?key=" + apiKey
                    + "&origin=" + URLEncoder.encode(originLoc, StandardCharsets.UTF_8)
                    + "&destination=" + URLEncoder.encode(destLoc, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            JsonNode root = mapper.readTree(resp.body());
            JsonNode path = root.path("route").path("paths").path(0);
            if (path.isMissingNode()) {
                logger.warn("高德未返回路线: status={}, info={}", root.path("status").asText(), root.path("info").asText());
                return new SkillResult(false, "没查到该路线的通勤信息");
            }

            long seconds = path.path("duration").asLong();
            long minutes = seconds / 60;
            long distanceMeters = path.path("distance").asLong();

            StringBuilder sb = new StringBuilder();
            sb.append("【通勤测算】").append(origin).append(" → ").append(destination).append("\n");
            sb.append(String.format("全程约 %.1f 公里 · 预计驾车 %d 分钟", distanceMeters / 1000.0, minutes)).append("\n");

            JsonNode steps = path.path("steps");
            StringBuilder route = new StringBuilder();
            int count = 0;
            for (JsonNode step : steps) {
                String instruction = step.path("instruction").asText("");
                if (!instruction.isEmpty()) {
                    if (route.length() > 0) route.append(" → ");
                    route.append(instruction);
                    if (++count >= 5) break;
                }
            }
            if (route.length() > 0) {
                sb.append("\n路线：").append(route).append("\n");
            }

            // 打车费用估算（本地计价规则：起步 3 公里 ¥10 + 超出约 ¥2.3/公里，实际以平台为准）——
            // 说明"打车为何用估算计价"：不做真实订单/跳转依赖，先给用户量级参考
            double km = distanceMeters / 1000.0;
            int taxiCost = 10 + (int) Math.round(Math.max(0, km - 3) * 2.3);
            sb.append("\n打车费用估算：约 ").append(taxiCost).append(" 元");

            // 打车/查看路线统一走 https 网页版（uri.amap.com 官方网页兼容方案，10-27 定案）：
            // 微信/QQ 浏览器/vivo 自带浏览器等一律拦截 amapuri:// 自定义 scheme（只有 Chrome/系统浏览器
            // 会弹"打开高德地图"确认框），https 链接任何浏览器/微信都能打开，页面内 callnative=1 会尝试唤起高德 App
            // 高德无独立打车 https 页（打车在 App 内），navigation 唤起 App 后即可打车/导航——两个入口共用此链接
            // 精简参数（只留坐标+mode，地名编码会让 URL 很长）：链接行只留 URL 方便微信自动识别可点
            sb.append("\n\n🚕 打车/查看路线（点击唤起高德 App）\n");
            sb.append("https://uri.amap.com/navigation?from=").append(originLoc)
                    .append("&to=").append(destLoc)
                    .append("&mode=car&callnative=1");
            return new SkillResult(true, sb.toString());
        } catch (Exception e) {
            return new SkillResult(false, "通勤测算失败: " + e.getMessage());
        }
    }

    // 地址 → 经纬度："经度,纬度"
    // 优先 rag-mcp 的 geo_cache（地址→坐标缓存，避免每次重复调高德）；无 rag 连接直接调高德；
    // geocode 失败时用 POI 关键词搜索兜底（v3/place/text，POI 命中更精确）
    private String resolveLocation(String name) {
        if (name.matches(COORD_PATTERN)) {
            return name;
        }
        if (ragConnection != null) {
            try {
                String cached = ragConnection.callTool("rag_internal_geocode", Map.of("address", name));
                if (cached != null && !cached.isBlank() && !cached.contains("未能定位")
                        && !cached.contains("失败") && !cached.contains("未配置")) {
                    return cached.trim();
                }
                logger.warn("rag geocode 未命中/失败: {}", cached);
            } catch (Exception e) {
                logger.warn("rag geocode 调用失败，降级直连高德: {}", e.getMessage());
            }
        }
        String geo = geocodeDirect(name);
        if (geo != null) {
            return geo;
        }
        return poiDirect(name);
    }

    // 直接调高德地理编码（无缓存兜底路径）
    private String geocodeDirect(String name) {
        try {
            String url = geocodeUrl + "?key=" + apiKey
                    + "&address=" + URLEncoder.encode(name, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(resp.body());
            if (!"1".equals(root.path("status").asText())) {
                logger.warn("高德地理编码失败: status={}, info={}", root.path("status").asText(), root.path("info").asText());
                return null;
            }
            JsonNode geocodes = root.path("geocodes");
            if (!geocodes.isArray() || geocodes.isEmpty()) {
                return null;
            }
            String location = geocodes.path(0).path("location").asText();
            return location.isEmpty() ? null : location;
        } catch (Exception e) {
            logger.warn("高德地理编码异常: {}", e.getMessage());
            return null;
        }
    }

    // POI 关键词搜索兜底（geocode 失败时）：v3/place/text 返回真实地点（比地址插值精确）
    private String poiDirect(String name) {
        try {
            String url = "https://restapi.amap.com/v3/place/text?key=" + apiKey
                    + "&keywords=" + URLEncoder.encode(name, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(resp.body());
            if (!"1".equals(root.path("status").asText())) {
                return null;
            }
            JsonNode pois = root.path("pois");
            if (!pois.isArray() || pois.isEmpty()) {
                return null;
            }
            return pois.path(0).path("location").asText();
        } catch (Exception e) {
            logger.warn("高德 POI 搜索异常: {}", e.getMessage());
            return null;
        }
    }
}