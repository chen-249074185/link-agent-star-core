package com.star.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.star.agent.tool.ToolExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class WeatherSkill implements ToolExecutor {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String name() {
        return "get_weather";
    }

    @Override
    public String execute(String args) {
        try {
            JsonNode node = mapper.readTree(args);
            String city = node.path("city").asText();
            return "查询 [" + city + "] 天气（占位，下一步接真实 API）";
        } catch (Exception e) {
            return "参数解析失败: " + e.getMessage();
        }
    }

    @Override
    public String description() {
        return "查询指定城市的天气";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "city", Map.of("type", "string", "description", "城市名")),
                "required", List.of("city")
        );
    }
}