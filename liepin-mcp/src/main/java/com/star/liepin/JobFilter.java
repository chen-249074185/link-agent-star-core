package com.star.liepin;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 岗位精筛器：按求职画像四维规则（学历/经验/薪资/城市）过滤搜索结果的岗位
// 输出：能投的岗位 + 每条"为什么适合"（可解释、可测试，不是 LLM 凭感觉）
public class JobFilter {

    private static final Pattern SALARY_PATTERN = Pattern.compile("(\\d+)\\s*-\\s*(\\d+)\\s*k", Pattern.CASE_INSENSITIVE);

    // 过滤：返回命中的岗位（含通过理由）；四维全过才算"能投"
    public static List<Match> filter(JsonNode jobs, FilterRules rules) {
        List<Match> matches = new ArrayList<>();
        for (JsonNode job : jobs) {
            List<String> passed = new ArrayList<>();
            if (educationOk(job, rules)) passed.add("学历");
            if (workYearsOk(job, rules)) passed.add("经验");
            if (salaryOk(job, rules)) passed.add("薪资");
            if (cityOk(job, rules)) passed.add("城市");
            if (passed.size() == 4) {
                matches.add(new Match(job, "四维全过: " + String.join("/", passed)));
            }
        }
        return matches;
    }

    // 学历：岗位要求 = 学历不限 或 等于画像学历 → 匹配
    private static boolean educationOk(JsonNode job, FilterRules rules) {
        String edu = job.path("education").asText("");
        return edu.isBlank() || "学历不限".equals(edu) || edu.contains(rules.education());
    }

    // 经验：岗位经验要求命中任一关键词（经验不限/应届/实习/1年以下）→ 匹配
    private static boolean workYearsOk(JsonNode job, FilterRules rules) {
        String years = job.path("workYears").asText("");
        if (years.isBlank()) return true;
        for (String kw : rules.workYearKeywords()) {
            if (years.contains(kw)) return true;
        }
        return false;
    }

    // 薪资：解析"10-20k"取下限 → 下限 ≥ 画像下限；面议/解析不出 → 放行（可谈）
    private static boolean salaryOk(JsonNode job, FilterRules rules) {
        String salary = job.path("salary").asText("");
        if (salary.isBlank() || salary.contains("面议")) return true;
        Matcher m = SALARY_PATTERN.matcher(salary);
        if (m.find()) {
            return Integer.parseInt(m.group(1)) >= rules.salaryFloorK();
        }
        return true;   // 格式不认识：别误杀，放行
    }

    // 城市：岗位地点命中任一期望城市 → 匹配（地点形如"北京-西二旗"）
    private static boolean cityOk(JsonNode job, FilterRules rules) {
        String loc = job.path("location").asText("");
        if (rules.cities().isEmpty()) return true;
        for (String city : rules.cities()) {
            if (loc.contains(city)) return true;
        }
        return false;
    }

    // 命中记录：岗位 + 通过理由（可解释输出）
    public record Match(JsonNode job, String reason) {
    }
}