package com.star.liepin;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

// 求职画像筛选规则（从 liepin-filter-rules.yaml 读取）：改配置不改代码
public record FilterRules(String education, List<String> workYearKeywords,
                          int salaryFloorK, List<String> cities) {

    // 从 classpath 加载规则（fat jar 内自带；解析失败/缺字段给保守默认值，不崩）
    @SuppressWarnings("unchecked")
    public static FilterRules load() {
        try (InputStream in = FilterRules.class.getResourceAsStream("/liepin-filter-rules.yaml")) {
            if (in == null) {
                return defaults();
            }
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> p = (Map<String, Object>) root.getOrDefault("profile", Map.of());
            String education = String.valueOf(p.getOrDefault("education", "本科"));
            List<String> keywords = p.get("workYearKeywords") instanceof List<?> l
                    ? l.stream().map(String::valueOf).toList() : List.of("经验不限", "应届");
            int salaryFloor = p.get("salaryFloorK") instanceof Number n ? n.intValue() : 0;
            List<String> cities = p.get("cities") instanceof List<?> l
                    ? l.stream().map(String::valueOf).toList() : List.of();
            return new FilterRules(education, keywords, salaryFloor, cities);
        } catch (Exception e) {
            return defaults();
        }
    }

    private static FilterRules defaults() {
        return new FilterRules("本科", List.of("经验不限", "应届", "实习"), 0, List.of());
    }
}