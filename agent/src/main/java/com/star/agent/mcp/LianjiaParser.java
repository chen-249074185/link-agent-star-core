package com.star.agent.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 链家租房列表页解析器：从 crawl-mcp 抓回的 HTML 中提取房源条目（标题/面积/租金）
// 页面结构（2026-08 实测）：每个房源一个 <div class="content__list--item"> 块，
//   标题在 content__list--item--title 的 <a> 里，面积在 content__list--item--des，租金在 item-price 的 <em>
// 注意：列表页可能夹带广告块（data-c_type 等），解析失败/缺字段的条目直接跳过，不崩
public class LianjiaParser {

    // 房源条目块：从 item 的 div 开始，到下一个 item div 或 <script> 为止
    private static final Pattern ITEM_BLOCK = Pattern.compile(
            "<div class=\"content__list--item\"[^>]*>(.*?)(?=<div\\s+class=\"content__list--item\"|<script|</body>|$)",
            Pattern.DOTALL);

    private static final Pattern TITLE = Pattern.compile(
            "content__list--item--title[^>]*>\\s*<a[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern AREA = Pattern.compile(
            "([\\d.]+)\\s*㎡");
    private static final Pattern PRICE = Pattern.compile(
            "content__list--item-price\"><em>(.*?)</em>");

    private LianjiaParser() {
    }

    public static List<House> parse(String html) {
        List<House> houses = new ArrayList<>();
        Matcher block = ITEM_BLOCK.matcher(html == null ? "" : html);
        while (block.find()) {
            String chunk = block.group(1);
            House h = parseOne(chunk);
            if (h != null) {
                houses.add(h);
            }
        }
        return houses;
    }

    // 从单个条目块提取；字段缺失视为脏数据跳过
    static House parseOne(String chunk) {
        Matcher t = TITLE.matcher(chunk);
        if (!t.find()) {
            return null;
        }
        String name = t.group(1).replaceAll("\\s+", " ").trim();
        if (name.isEmpty()) {
            return null;
        }
        Matcher a = AREA.matcher(chunk);
        double area = a.find() ? Double.parseDouble(a.group(1)) : 0;
        Matcher p = PRICE.matcher(chunk);
        if (!p.find()) {
            return null;
        }
        // 租金可能是一段区间（如 3333-3444）：取下限（保守估计）
        String priceText = p.group(1).trim();
        int price;
        try {
            int dash = priceText.indexOf('-');
            price = Integer.parseInt(dash >= 0 ? priceText.substring(0, dash).trim() : priceText);
        } catch (NumberFormatException e) {
            return null;
        }
        return new House(name, area, price);
    }

    // 房源条目（解析产物）：名称/面积(㎡)/月租(元)
    public record House(String name, double area, int price) {
    }
}