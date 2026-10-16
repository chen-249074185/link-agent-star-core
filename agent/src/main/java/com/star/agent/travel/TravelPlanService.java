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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 旅行规划编排服务（按本项目模式实现）：
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

    // 当天最晚收工 21:30：防排程溢出（容量限制外再兜底，杜绝 23:30/25:30/27:30 这种跨天时间）
    private static final double DAY_END = 21.5;

    // 常见预算档位 + 典型规划（预算不足时推荐给用户，静态建议无需额外调用）
    private static final List<Map<String, Object>> BUDGET_PRESETS = List.of(
            budgetPreset(300, "经济型：青旅/快捷酒店 + 公交地铁出行 + 人均 30~50 平价餐厅 + 免费/低门票景点（公园、博物馆、老街）"),
            budgetPreset(500, "舒适型：经济酒店 + 地铁为主偶尔打车 + 人均 50~80 特色餐厅 + 当地热门景点"),
            budgetPreset(800, "轻奢型：舒适酒店 + 打车为主 + 人均 80~120 口碑餐厅 + 门票景点 + 特色体验"),
            budgetPreset(1200, "豪华型：高档酒店 + 全程打车 + 人均 120+ 品质餐厅 + 深度体验/演出")
    );

    private static Map<String, Object> budgetPreset(int level, String plan) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", "¥" + level + "/天");
        m.put("plan", plan);
        return m;
    }

    private final AmapPoiService amapPoiService;
    private final WeatherSkill weatherSkill;

    public TravelPlanService(AmapPoiService amapPoiService, WeatherSkill weatherSkill) {
        this.amapPoiService = amapPoiService;
        this.weatherSkill = weatherSkill;
    }

    // 入口（兼容旧调用：不填预算 = 无上限）
    public Map<String, Object> plan(String location, String keyword, Integer days) {
        return plan(location, keyword, days, null);
    }

    // 入口：location 目的地（必填）；keyword 景点关键词（默认"景点"）；days 天数 1~7；budgetPerDay 预算（元/天，null=无上限）
    // 失败返回带 error 的 Map（页面友好提示，不抛异常）
    public Map<String, Object> plan(String location, String keyword, Integer days, Integer budgetPerDay) {
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
        Integer budget = (budgetPerDay == null || budgetPerDay <= 0) ? null : budgetPerDay;

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

        // 预算过滤：有价格且超预算的剔除、无价格信息保留；全超预算 → 提示预算不足 + 推荐常见预算档位
        boolean insufficient = false;
        List<Map<String, String>> affordable = restaurants;
        if (budget != null) {
            List<Map<String, String>> fit = new ArrayList<>();
            for (Map<String, String> r : restaurants) {
                Integer c = parseCost(r.getOrDefault("cost", ""));
                if (c == null || c <= budget) {
                    fit.add(r);
                }
            }
            if (fit.isEmpty()) {
                insufficient = true;
            } else {
                affordable = fit;
            }
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
            dayPlan.add(buildDay(i + 1, spots, affordable, restCursor, coords));
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
        data.put("restaurants", buildRestaurantList(affordable, coords));
        data.put("budget", buildBudget(affordable, d, budget, insufficient));
        return data;
    }

    // 过滤乐园类后顺序分天：第 i 个景点进第 i/3 天；每天容量 3（超出丢弃，保留高德排序靠前的）
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
            if (idx >= days * MAX_SPOTS_PER_DAY) {
                break; // 容量 3/天已满：不再挤进最后一天（否则该天景点过多，排程时间溢出到次日）
            }
            result.get(idx / MAX_SPOTS_PER_DAY).add(c);
            idx++;
        }
        return result;
    }

    // 生成某一天：09:00 开始，每个景点 2 小时，12:00-13:30 午餐保护（自动插餐厅），附导航链接（from=目的地中心）
    private Map<String, Object> buildDay(int day, List<Map<String, String>> spots,
                                         List<Map<String, String>> restaurants, int[] restCursor, String fromCoords) {
        List<Map<String, Object>> schedule = new ArrayList<>();
        double start = 9.0;
        boolean lunchAdded = false;
        for (int i = 0; i < spots.size(); i++) {
            if (start >= DAY_END) {
                break; // 当天排满 21:30 收工，剩余景点不再排（容量外的兜底）
            }
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
                schedule.add(lunchEntry(restaurants, restCursor, fromCoords));
                lunchAdded = true;
                start = 13.5;
            }
            schedule.add(spotEntry(spot, start, dur, fromCoords));
            start += dur;
        }
        // 收尾补午餐（上午有活动但还没插过）
        if (!lunchAdded && start >= 10.5) {
            schedule.add(lunchEntry(restaurants, restCursor, fromCoords));
        }

        Map<String, Object> dayMap = new LinkedHashMap<>();
        dayMap.put("day", day);
        dayMap.put("schedule", schedule);
        return dayMap;
    }

    private Map<String, Object> spotEntry(Map<String, String> spot, double start, double dur, String fromCoords) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "spot");
        entry.put("time", formatTime(start));
        entry.put("duration", formatDur(dur));
        entry.put("name", spot.getOrDefault("name", ""));
        entry.put("distance", formatDistance(spot.get("distance")));
        entry.put("address", spot.getOrDefault("address", ""));
        entry.put("navUrl", amapPoiService.buildNavLink(fromCoords, spot.get("location")));
        return entry;
    }

    private Map<String, Object> lunchEntry(List<Map<String, String>> restaurants, int[] cursor, String fromCoords) {
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
            entry.put("navUrl", amapPoiService.buildNavLink(fromCoords, r.get("location")));
        } else {
            entry.put("name", "附近餐厅");
        }
        return entry;
    }

    private List<Map<String, Object>> buildRestaurantList(List<Map<String, String>> restaurants, String fromCoords) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, String> r : restaurants) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", r.getOrDefault("name", ""));
            item.put("cost", r.getOrDefault("cost", ""));
            item.put("rating", r.getOrDefault("rating", ""));
            item.put("distance", formatDistance(r.get("distance")));
            item.put("address", r.getOrDefault("address", ""));
            item.put("navUrl", amapPoiService.buildNavLink(fromCoords, r.get("location")));
            list.add(item);
        }
        return list;
    }

    // 预算：餐饮真实人均（推荐店 min~max，日均 avg*2）+ 交通估算 + 门票现场
    // 预算内无合适餐厅（insufficient）→ 提示预算不足 + 推荐常见预算档位典型规划
    private Map<String, Object> buildBudget(List<Map<String, String>> restaurants, int days,
                                            Integer budget, boolean insufficient) {
        Map<String, Object> b = new LinkedHashMap<>();
        List<Integer> costs = new ArrayList<>();
        for (Map<String, String> r : restaurants) {
            Integer c = parseCost(r.getOrDefault("cost", ""));
            if (c != null) {
                costs.add(c);
            }
        }
        if (budget == null) {
            b.put("limit", "未设置预算上限，按常规消费估算");
        } else if (insufficient) {
            b.put("limit", "⚠️ 预算 ¥" + budget + "/天 内没有合适餐厅，当前行程按常规规划仅供参考");
            b.put("suggestTip", "可参考以下常见预算档位的典型规划，点击直接按该档位重新规划：");
            b.put("suggestions", BUDGET_PRESETS);
        } else {
            b.put("limit", "预算上限 ¥" + budget + "/天");
        }
        if (!costs.isEmpty()) {
            int min = Collections.min(costs);
            int max = Collections.max(costs);
            int avg = (int) costs.stream().mapToInt(Integer::intValue).average().orElse(0);
            b.put("food", "推荐店人均 ¥" + min + " ~ ¥" + max + "，日均约 ¥" + (avg * 2) + "（午餐+晚餐）");
            if (budget != null && !insufficient) {
                int est = avg * 2 + (30 + days * 10);
                b.put("estimate", "估算约 ¥" + est + "/天，" + (est <= budget ? "在预算内" : "超出预算（可减少景点或选平价餐厅）"));
            }
        } else {
            b.put("food", "以各门店实际价格为准");
        }
        b.put("transport", "约 ¥" + (30 + days * 10) + "/天（估算）");
        b.put("ticket", "以各景点现场实际为准");
        return b;
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

    // 解析人均价格：取第一个数字；"30-50元/人" 取下限 30（不能把数字拼成 3050）；无数字返回 null
    private Integer parseCost(String cost) {
        if (cost == null || cost.isBlank()) {
            return null;
        }
        Matcher m = Pattern.compile("\\d+").matcher(cost);
        if (!m.find()) {
            return null;
        }
        try {
            return Integer.parseInt(m.group());
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