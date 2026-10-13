package com.star.agent.travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.star.agent.infra.config.AppConfig;
import com.star.agent.skill.JsonSchemaBuilder;
import com.star.agent.skill.RiskLevel;
import com.star.agent.skill.Skill;
import com.star.agent.skill.SkillContext;
import com.star.agent.skill.SkillMeta;
import com.star.agent.skill.SkillResult;
import com.star.agent.skill.SkillType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 高德 POI 服务（借鉴 traces-in-the-clouds AmapPoiService 结构，本项目模式改造）：
// 地理编码 / 周边搜索（带人均消费·评分）/ 关键词搜索 / 导航链接生成
// 全程 Web 服务 key（AMAP_KEY）后端持有，不下发前端
@Component
public class AmapPoiService {

    private static final String GEOCODE_URL = "https://restapi.amap.com/v3/geocode/geo";
    private static final String AROUND_URL = "https://restapi.amap.com/v3/place/around";
    private static final String TEXT_URL = "https://restapi.amap.com/v3/place/text";
    private static final String NAVI_URL = "https://uri.amap.com/navigation";

    private final String amapKey;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();

    @Autowired
    public AmapPoiService(AppConfig config) {
        this.amapKey = config.amapApiKey();
    }

    public boolean hasApiKey() {
        return amapKey != null && !amapKey.isBlank();
    }

    // 地名 → "lng,lat"，查不到返回 null
    public String geoCode(String address) {
        String url = GEOCODE_URL + "?key=" + amapKey + "&address=" + encode(address);
        JsonNode root = get(url);
        JsonNode geocodes = root == null ? null : root.path("geocodes");
        if (geocodes == null || !geocodes.isArray() || geocodes.isEmpty()) {
            return null;
        }
        return geocodes.get(0).path("location").asText("");
    }

    // 周边搜索：坐标 + 半径(米) + 关键词；ext=true 额外返回人均消费/评分（extensions=all）
    public List<Map<String, String>> searchNearby(String location, int radius, String keywords, int count, boolean ext) {
        String url = AROUND_URL + "?key=" + amapKey
                + "&location=" + location
                + "&radius=" + radius
                + "&keywords=" + encode(keywords)
                + "&offset=" + count
                + "&page=1"
                + (ext ? "&extensions=all" : "");
        return parsePois(get(url), ext);
    }

    // 关键词搜索（周边搜不到时兜底：城市级）
    public List<Map<String, String>> searchByText(String city, String keywords, int count) {
        String url = TEXT_URL + "?key=" + amapKey
                + "&keywords=" + encode(keywords)
                + "&city=" + encode(city)
                + "&offset=" + count
                + "&page=1";
        return parsePois(get(url), false);
    }

    // 高德 URI 导航链接：from 起点坐标 + to 终点坐标（不加中文地名参数，URL 短不易被浏览器拦截；callnative=1 唤起 App）
    public String buildNavLink(String from, String to) {
        if (from == null || to == null || from.isBlank() || to.isBlank()) {
            return "";
        }
        String[] f = from.split(",");
        String[] t = to.split(",");
        if (f.length < 2 || t.length < 2) {
            return "";
        }
        return NAVI_URL + "?from=" + f[0] + "," + f[1]
                + "&to=" + t[0] + "," + t[1]
                + "&mode=car&callnative=1";
    }

    private List<Map<String, String>> parsePois(JsonNode root, boolean ext) {
        List<Map<String, String>> results = new ArrayList<>();
        JsonNode pois = root == null ? null : root.path("pois");
        if (pois == null || !pois.isArray()) {
            return results;
        }
        for (JsonNode poi : pois) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("name", poi.path("name").asText(""));
            item.put("address", poi.path("address").asText(""));
            item.put("location", poi.path("location").asText(""));
            item.put("distance", poi.path("distance").asText(""));
            item.put("type", poi.path("type").asText(""));
            if (ext) {
                item.put("cost", poi.path("biz_ext").path("cost").asText(""));
                item.put("rating", poi.path("biz_ext").path("rating").asText(""));
            }
            results.add(item);
        }
        return results;
    }

    private JsonNode get(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                return null;
            }
            return new ObjectMapper().readTree(res.body());
        } catch (Exception e) {
            return null;
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}