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

// 心知天气真实接入：get_weather 技能（已按 Skill 接口实现）
@Component
public class WeatherSkill implements Skill {

    private static final String DEFAULT_BASE_URL = "https://api.seniverse.com/v3/weather/now.json";

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public WeatherSkill(AppConfig appConfig) {
        this(DEFAULT_BASE_URL, appConfig.seniverseApiKey());
    }

    // 测试用：注入假服务器地址 + 任意 key，不打真实心知天气
    public WeatherSkill(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    @Override
    public SkillMeta meta() {
        return new SkillMeta(
                "get_weather",
                "查询指定城市的天气",
                JsonSchemaBuilder.object()
                        .addString("city", "城市名", true)
                        .build(),
                RiskLevel.LOW,
                List.of("CHAT", "DATA"),
                "LOCAL",
                SkillType.EXT
        );
    }

    @Override
    public SkillResult execute(SkillContext ctx) {
        try {
            JsonNode node = mapper.readTree(ctx.args());
            String city = node.path("city").asText();
            if (city.isEmpty()) return new SkillResult(false, "请告诉我要查询哪个城市");

            // 拼 GET 请求：key + 城市 + 简体中文 + 摄氏度
            String url = baseUrl + "?key=" + apiKey
                    + "&location=" + URLEncoder.encode(city, StandardCharsets.UTF_8)
                    + "&language=zh-Hans&unit=c";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // 解析：results[0].now 里有 text(天气) 和 temperature(温度)
            JsonNode root = mapper.readTree(resp.body());
            JsonNode now = root.path("results").path(0).path("now");
            if (now.isMissingNode()) {
                return new SkillResult(false, "没查到[" + city + "]的天气，试试加上省市，如：北京市");
            }
            String text = now.path("text").asText();
            String temp = now.path("temperature").asText();
            return new SkillResult(true, "[" + city + "] 当前天气：" + text + "，" + temp + "℃");
        } catch (Exception e) {
            return new SkillResult(false, "天气查询失败: " + e.getMessage());
        }
    }
}