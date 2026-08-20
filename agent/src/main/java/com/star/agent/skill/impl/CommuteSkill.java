package com.star.agent.skill.impl;

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
import java.util.List;

// 高德路线规划真实接入：commute_estimate 技能（MAIN 主线：租房通勤成本测算）
@Component
public class CommuteSkill implements Skill {

    private static final String DEFAULT_BASE_URL = "https://restapi.amap.com/v3/direction/driving";

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public CommuteSkill(AppConfig appConfig) {
        this(DEFAULT_BASE_URL, appConfig.amapApiKey());
    }

    // 测试用：注入假服务器地址 + 任意 key，不打真实高德
    public CommuteSkill(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    @Override
    public SkillMeta meta() {
        return new SkillMeta(
                "commute_estimate",
                "测算两个地点之间的通勤时间，输入出发地和目的地的经纬度",
                JsonSchemaBuilder.object()
                        .addString("origin", "出发地经纬度，如 116.481028,39.989643", true)
                        .addString("destination", "目的地经纬度，如 116.465302,40.004717", true)
                        .build(),
                RiskLevel.LOW,
                List.of("CHAT"),
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
            if (origin.isEmpty() || destination.isEmpty()) {
                return new SkillResult(false, "请提供出发地和目的地的经纬度");
            }

            String url = baseUrl + "?key=" + apiKey
                    + "&origin=" + URLEncoder.encode(origin, StandardCharsets.UTF_8)
                    + "&destination=" + URLEncoder.encode(destination, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            JsonNode root = mapper.readTree(resp.body());
            JsonNode path = root.path("route").path("paths").path(0);
            if (path.isMissingNode()) {
                return new SkillResult(false, "没查到该路线的通勤信息");
            }
            int seconds = path.path("duration").asInt();
            int minutes = seconds / 60;
            return new SkillResult(true, "预计通勤时间约" + minutes + "分钟");
        } catch (Exception e) {
            return new SkillResult(false, "通勤测算失败: " + e.getMessage());
        }
    }
}