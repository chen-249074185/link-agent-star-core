package com.star.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 房源文本 → 结构化 JSON 转换桥（M3 双轨切换暴露的接口契约适配）
// 场景：Mock RagSearchSkill 返回结构化房源 JSON（原样透传）；真实 MCP RAG 返回自由文本
// （"[score=0.719] 北京海淀学院路两居室月租6500元，通勤中关村35分钟。"）→ 正则提取结构化字段
public class HouseTextParser {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Pattern PRICE = Pattern.compile("(\\d+)\\s*元");
    private static final Pattern COMMUTE = Pattern.compile("(\\d+)\\s*分钟");
    private static final Pattern SCORE_PREFIX = Pattern.compile("^\\[score=[^\\]]*\\]\\s*");

    // 入口：文本 → 房源 JSON 数组字符串（无房源返回 []）
    public static String toJson(String text) {
        if (text == null) {
            return "[]";
        }
        String t = text.trim();
        // 已是 JSON（Mock 数据/调用方已结构化）：原样透传，不二次解析
        // 注意：不能只判断 [ 开头——真实 RAG 文本是 "[score=0.719] ..." 也会 [ 开头！
        if (t.startsWith("[{") || t.startsWith("{\"")) {
            return t;
        }
        List<Map<String, Object>> houses = new ArrayList<>();
        for (String line : t.split("\n")) {
            String clean = SCORE_PREFIX.matcher(line).replaceFirst("").trim();
            if (clean.isBlank()) {
                continue;
            }
            int price = firstInt(PRICE, clean);
            if (price <= 0) {
                continue;   // 没价格信息的行不算房源
            }
            int commute = firstInt(COMMUTE, clean);
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("name", extractName(clean));
            h.put("price", price);
            h.put("commute", commute);
            h.put("area", 50);      // 文本无面积信息 → 默认值（打分/风险技能可正常处理）
            h.put("rating", 7);
            houses.add(h);
        }
        try {
            return mapper.writeValueAsString(houses);
        } catch (Exception e) {
            return "[]";
        }
    }

    // 房源名：取"月租/价格/租金"之前的片段（去掉价格段）；取不到给整行前 12 字
    private static String extractName(String line) {
        int idx = line.indexOf("月租");
        if (idx <= 0) idx = line.indexOf("价格");
        if (idx <= 0) idx = line.indexOf("租金");
        String name = idx > 0 ? line.substring(0, idx) : line;
        return name.length() > 12 ? name.substring(0, 12) : name;
    }

    private static int firstInt(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }
}