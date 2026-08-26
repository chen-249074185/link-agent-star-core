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

// 心知天气真实接入：get_weather 技能
// 支持当前天气（now.json）+ 未来预报（daily.json，days=2 含明天）——
// 补"明天"能力后模型不再需要爬天气网页试错（实测：问"明天天气"模型爬了 5 次中国天气网还失败）
@Component
public class WeatherSkill implements Skill {

    private static final String DEFAULT_BASE_URL = "https://api.seniverse.com/v3/weather/";

    private final String baseUrl;   // host（末尾带 /），execute 按需拼 now.json / daily.json
    private final String apiKey;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public WeatherSkill(AppConfig appConfig) {
        this(DEFAULT_BASE_URL, appConfig.seniverseApiKey());
    }

    // 测试用：注入假服务器地址 + 任意 key，不打真实心知天气
    public WeatherSkill(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.apiKey = apiKey;
    }

    @Override
    public SkillMeta meta() {
        return new SkillMeta(
                "get_weather",
                "查询指定城市当前天气或未来几天预报（days：1=仅当前，2=今天+明天，3=未来三天）",
                JsonSchemaBuilder.object()
                        .addString("city", "城市名", true)
                        .addInteger("days", "预报天数 1~3，问明天/未来几天时传 2 或 3", false)
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
            int days = node.path("days").asInt(1);
            if (days < 1) days = 1;
            if (days > 3) days = 3;

            String path = days == 1 ? "now.json" : "daily.json";
            String url = baseUrl + path + "?key=" + apiKey
                    + "&location=" + URLEncoder.encode(city, StandardCharsets.UTF_8)
                    + "&language=zh-Hans&unit=c";
            if (days > 1) {
                url += "&start=0&days=" + days;
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            JsonNode root = mapper.readTree(resp.body());
            JsonNode result0 = root.path("results").path(0);
            if (result0.isMissingNode()) {
                return new SkillResult(false, "没查到[" + city + "]的天气，试试加上省市，如：北京市");
            }
            if (days == 1) {
                JsonNode now = result0.path("now");
                if (now.isMissingNode()) {
                    return new SkillResult(false, "没查到[" + city + "]的天气，试试加上省市，如：北京市");
                }
                return new SkillResult(true, "[" + city + "] 当前天气：" + now.path("text").asText()
                        + "，" + now.path("temperature").asText() + "℃");
            }
            // 逐日预报：daily[i] = 今天起第 i 天（date/text_day/text_night/high/low）
            StringBuilder sb = new StringBuilder("[" + city + "] 未来 " + days + " 天预报：\n");
            int i = 0;
            for (JsonNode d : result0.path("daily")) {
                if (i >= days) break;
                String label = i == 0 ? "今天" : (i == 1 ? "明天" : "后天");
                sb.append(label).append("（").append(d.path("date").asText()).append("）：")
                        .append(d.path("text_day").asText()).append("/")
                        .append(d.path("text_night").asText())
                        .append("，").append(d.path("low").asText()).append("~")
                        .append(d.path("high").asText()).append("℃\n");
                i++;
            }
            return new SkillResult(true, sb.toString().trim());
        } catch (Exception e) {
            return new SkillResult(false, "天气查询失败: " + e.getMessage());
        }
    }
}