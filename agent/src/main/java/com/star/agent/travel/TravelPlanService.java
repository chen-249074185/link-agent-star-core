package com.star.agent.travel;

import com.star.agent.skill.SkillContext;
import com.star.agent.skill.impl.WeatherSkill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 旅行规划编排服务（借鉴 traces-in-the-clouds PlanController 纯算法思路，本项目模式改造）：
// 目的地 → 高德地理编码 → 周边景点/美食搜索（真实数据）→ 分天排程（每天≤3、午餐保护）→ 天气 → 预算
// 返回结构化 Map（页面渲染用），TripPlanSkill 包一层转可读文本（聊天用）
@Component
public class TravelPlanService {

    private static final Logger logger = LoggerFactory.getLogger(TravelPlanService.class);

    // 不适合短途一日游的大型乐园类关键词（高德周边搜出来的乐园全过滤）
    private static final String[] BAD_KEYWORDS = {
            "迪士尼", "环球影城", "欢乐谷", "长隆", "动物园", "野生动物园",
            "影视城", "度假区", "游乐园", "方特"
    };

    private static final int MAX_SPOTS_PER_DAY = 3;

    private final AmapPoiService amapPoiService;
    private final WeatherSkill weatherSkill;

    public TravelPlanService(AmapPoiService amapPoiService, WeatherSkill weatherSkill) {
        this.amapPoiService = amapPoiService;
        this.weatherSkill = weatherSkill;
    }

    // 入口：location 目的地（必填）；keyword 景点关键词（默认"景点"）；days 天数 1~7
    // 失败返回带 error 的 Map（页面友好提示，不抛异常）
    public Map<String, Object> plan(String location, String keyword, Integer days) {
        Map<String, Object> fail = new LinkedHashMap<>();
        if (location == null || location.isBlank()) {
            fail.put("error", "请输入目的地");
            return fail;
        }
        if (!amapPoiService.hasApiKey()) {
            fail.put("error", "高德 Web 服务 Key 未配置");
            return fail;
        }
        String kw = (keyword == null || keyword.isBlank()) ? "景点" : keyword.trim();
        int d = days == null ? 1 : Math.max(1, Math.min(7, days));

        // ① 地理编码：目的地 → 坐标（失败 = 地点名无效或 key 失效）
        String coords = amapPoiService.geoCode(location);
        if (coords == null) {
            fail.put("error", "未找到位置「" + location + "」，请确认地点名称");
            return fail;
        }

        // ② 周边搜景点 + 美食（真实数据；周边无结果兜底城市级关键词）
        List<Map<String, String>> candidates = amapPoiService.searchNearby(coords, 20000, kw, 6 * d, false);
        if (candidates.isEmpty()) {
            candidates = amapPoiService.searchByText(location, kw, 6 * d);
        }
        if (candidates.isEmpty()) {
            fail.put("error", location + " 周边暂未找到「" + kw + "」相关景点");
            return fail;
        }
        List<Map<String, String>> restaurants = amapPoiService.searchNearby(coords, 20000, "餐饮", 5 * d, true);
        if (restaurants.isEmpty()) {
            restaurants = amapPoiService.searchByText(location, "美食|餐厅|饭店", 5 * d);
        }

        // ③ 分天排程：过滤乐园类 → 每天最多 3 个 → 生成逐日行程（含午餐保护 + 导航链接）
        List<List<Map<String, String>>> daysPlan = assignToDays(candidates, d);
        int[] restCursor = {0};
        List<Map<String, Object>> dayPlan = new ArrayList<>();
        for (int i = 0; i < d; i++) {
            List<Map<String, String>> spots = daysPlan.get(i);
            if (spots.isEmpty()) {
                continue;
            }
            dayPlan.add(buildDay(i + 1, spots, restaurants, restCursor));
        }
        if (dayPlan.isEmpty()) {
            fail.put("error", location + " 周边景点都被过滤掉了（仅保留市区景点），换个关键词试试");
            return fail;
        }

        // ④ 天气（心知天气，失败降级 null 前端隐藏）
        String weather = weatherSkill == null ? null : queryWeather(location);

        // ⑤ 汇总
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("location", location);
        data.put("keyword", kw);
        data.put("days", d);
        data.put("weather", weather);
        data.put("dayPlan", dayPlan);
        data.put("restaurants", buildRestaurantList(restaurants));
        data.put("budget", buildBudget(restaurants, d));
        return data;
    }

    // 过滤乐园类后顺序分天：第 i 个景点进第 i/3 天
    private List<List<Map<String, String>>> assignToDays(List<Map<String, String>> candidates, int days) {
        List<List<Map<String, String>>> result = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            result.add(new ArrayList<>());
        }
        int idx = 0;
        for (Map<String, String> c : candidates) {
            if (isBadSpot(c.get("name"))) {
                continue;
            }
            int day = Math.min(days - 1, idx / MAX_SPOTS_PER_DAY);
            result.get(day).add(c);
            idx++;
        }
        return result;
    }

    // 生成某一天：09:00 开始，每个景点 2 小时，12:00-13:30 午餐保护（自动插餐厅），附导航链接
    private Map<String, Object> buildDay(int day, List<Map<String, String>> spots,
                                         List<Map<String, String>> restaurants, int[] restCursor) {
        List<Map<String, Object>> schedule = new ArrayList<>();
        double start = 9.0;
        boolean lunchAdded = false;
        for (int i = 0; i < spots.size(); i++) {
            Map<String, String> spot = spots.get(i);
            double dur = 2.0;
            // 午餐保护：景点跨过 12:00 则截断到 12:00（不足 0.5h 直接跳到午饭后）
            if (start < 12.0 && start + dur > 12.0) {
                dur = 12.0 - start;
                if (dur < 0.5) {
                    start = 13.5;
                    i--;
                    continue;
                }
            }
            // 午餐窗口：12:00~13:30 未插过午餐 → 插午餐
            if (start >= 12.0 && start < 13.5 && !lunchAdded) {
                schedule.add(lunchEntry(restaurants, restCursor));
                lunchAdded = true;
                start = 13.5;
            }
            schedule.add(spotEntry(spot, start, dur));
            start += dur;
        }
        // 收尾补午餐（上午有活动但还没插过）
        if (!lunchAdded && start >= 10.5) {
            schedule.add(lunchEntry(restaurants, restCursor));
        }

        Map<String, Object> dayMap = new LinkedHashMap<>();
        dayMap.put("day", day);
        dayMap.put("schedule", schedule);
        return dayMap;
    }

    private Map<String, Object> spotEntry(Map<String, String> spot, double start, double dur) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "spot");
        entry.put("time", formatTime(start));
        entry.put("duration", formatDur(dur));
        entry.put("name", spot.getOrDefault("name", ""));
        entry.put("distance", formatDistance(spot.get("distance")));
        entry.put("address", spot.getOrDefault("address", ""));
        entry.put("navUrl", amapPoiService.buildNavLink(spot.get("location"), spot.getOrDefault("name", "")));
        return entry;
    }

    private Map<String, Object> lunchEntry(List<Map<String, String>> restaurants, int[] cursor) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "lunch");
        entry.put("time", "12:00");
        entry.put("duration", "1.5小时");
        if (restaurants != null && !restaurants.isEmpty()) {
            Map<String, String> r = restaurants.get(cursor[0] % restaurants.size());
            cursor[0]++;
            entry.put("name", r.getOrDefault("name", "附近餐厅"));
            entry.put("cost", r.getOrDefault("cost", ""));
            entry.put("address", r.getOrDefault("address", ""));
            entry.put("navUrl", amapPoiService.buildNavLink(r.get("location"), r.getOrDefault("name", "")));
        } else {
            entry.put("name", "附近餐厅");
        }
        return entry;
    }

    private List<Map<String, Object>> buildRestaurantList(List<Map<String, String>> restaurants) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, String> r : restaurants) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", r.getOrDefault("name", ""));
            item.put("cost", r.getOrDefault("cost", ""));
            item.put("rating", r.getOrDefault("rating", ""));
            item.put("distance", formatDistance(r.get("distance")));
            item.put("address", r.getOrDefault("address", ""));
            item.put("navUrl", amapPoiService.buildNavLink(r.get("location"), r.getOrDefault("name", "")));
            list.add(item);
        }
        return list;
    }

    // 预算：餐饮真实人均（推荐店 min~max，日均 avg*2）+ 交通估算 + 门票现场
    private Map<String, Object> buildBudget(List<Map<String, String>> restaurants, int days) {
        Map<String, Object> budget = new LinkedHashMap<>();
        List<Integer> costs = new ArrayList<>();
        for (Map<String, String> r : restaurants) {
            Integer c = parseCost(r.getOrDefault("cost", ""));
            if (c != null) {
                costs.add(c);
            }
        }
        if (!costs.isEmpty()) {
            int min = Collections.min(costs);
            int max = Collections.max(costs);
            int avg = (int) costs.stream().mapToInt(Integer::intValue).average().orElse(0);
            budget.put("food", "推荐店人均 ¥" + min + " ~ ¥" + max + "，日均约 ¥" + (avg * 2));
        } else {
            budget.put("food", "以各门店实际价格为准");
        }
        budget.put("transport", "约 ¥" + (30 + days * 10) + "/天（估算）");
        budget.put("ticket", "以各景点现场实际为准");
        return budget;
    }

    // 天气：复用 WeatherSkill（心知真实 API），入参拼 {city} 调 execute，失败返回 null
    private String queryWeather(String city) {
        try {
            String args = "{\"city\":\"" + city.replace("\"", "") + "\"}";
            var result = weatherSkill.execute(new SkillContext("", args, ""));
            if (result != null && result.success() && result.content() != null) {
                return result.content();
            }
        } catch (Exception e) {
            logger.warn("旅行天气查询失败: {}", e.getMessage());
        }
        return null;
    }

    private boolean isBadSpot(String name) {
        if (name == null) {
            return false;
        }
        for (String kw : BAD_KEYWORDS) {
            if (name.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    private Integer parseCost(String cost) {
        if (cost == null || cost.isBlank()) {
            return null;
        }
        String digits = cost.replaceAll("[^0-9]", "");
        if (digits.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String formatDistance(String distStr) {
        if (distStr == null || distStr.isBlank()) {
            return "";
        }
        try {
            int meters = Integer.parseInt(distStr.trim());
            if (meters >= 1000) {
                return String.format("%.1f公里", meters / 1000.0);
            }
            return meters + "米";
        } catch (NumberFormatException e) {
            return "";
        }
    }

    private String formatTime(double h) {
        int hour = (int) h;
        int min = (int) ((h - hour) * 60);
        return String.format("%02d:%02d", hour, min);
    }

    private String formatDur(double h) {
        if (h < 1) {
            return (int) (h * 60) + "分钟";
        }
        if (h == (int) h) {
            return (int) h + "小时";
        }
        return String.format("%.1f小时", h);
    }
}