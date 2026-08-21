package com.star.agent.service;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 租房需求解析器：从用户一句话里抓 城市+预算+通勤 三要素
// 三要素齐全 → 返回 RentRequest（触发工作流模式）；缺任一 → 返回 null（普通对话，走自由问答）
@Component
public class RentRequestDetector {

    // 三个正则预编译一次（别在 parse 里重复 new）
    private static final Pattern CITY_PATTERN = Pattern.compile("在(.+?)租房");
    private static final Pattern PRICE_PATTERN = Pattern.compile("预算(\\d+)");
    private static final Pattern COMMUTE_PATTERN = Pattern.compile("通勤(\\d+)分钟");

    public RentRequest parse(String message) {
        // ① 抓城市：找不到直接返回 null（非工作流消息）
        Matcher cityMatcher = CITY_PATTERN.matcher(message);
        if (!cityMatcher.find()) return null;
        String city = cityMatcher.group(1);

        // ② 抓预算：缺 → null
        Matcher priceMatcher = PRICE_PATTERN.matcher(message);
        if (!priceMatcher.find()) return null;
        int maxPrice = Integer.parseInt(priceMatcher.group(1));

        // ③ 抓通勤上限：缺 → null
        Matcher commuteMatcher = COMMUTE_PATTERN.matcher(message);
        if (!commuteMatcher.find()) return null;
        int maxCommute = Integer.parseInt(commuteMatcher.group(1));

        return new RentRequest(city, maxPrice, maxCommute);
    }
}