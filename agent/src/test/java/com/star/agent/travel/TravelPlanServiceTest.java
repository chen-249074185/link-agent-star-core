package com.star.agent.travel;

import com.star.agent.skill.SkillContext;
import com.star.agent.skill.SkillResult;
import com.star.agent.skill.impl.WeatherSkill;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 旅游规划编排测试（mock 高德 POI 服务，不打真实 API）：
// 编排正确性：景点过滤（乐园）/分天排程（每天≤3）/午餐保护/天气降级/预算
class TravelPlanServiceTest {

    private AmapPoiService amap;
    private WeatherSkill weather;
    private TravelPlanService service;

    private void setUp(List<Map<String, String>> spots, List<Map<String, String>> rests) {
        amap = mock(AmapPoiService.class);
        when(amap.hasApiKey()).thenReturn(true);
        when(amap.geoCode("杭州")).thenReturn("120.15,30.28");
        when(amap.searchNearby(anyString(), anyInt(), anyString(), anyInt(), anyBoolean())).thenReturn(spots);
        when(amap.searchByText(anyString(), anyString(), anyInt())).thenReturn(spots);
        when(amap.buildNavLink(anyString(), anyString())).thenReturn("https://uri.amap.com/navigation?to=x");
        weather = mock(WeatherSkill.class);
        when(weather.execute(any())).thenReturn(new SkillResult(true, "[杭州] 当前天气：晴，28℃"));
        service = new TravelPlanService(amap, weather);
    }

    private static Map<String, String> spot(String name, String location) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("location", location);
        m.put("address", "杭州某地");
        m.put("distance", "500");
        return m;
    }

    // 4 个景点 → 每天最多 3 个 → 第 2 天 1 个
    @Test
    void plan_assignsSpotsToDays_max3PerDay() {
        setUp(List.of(spot("西湖", "120.1,30.2"), spot("灵隐寺", "120.1,30.2"),
                spot("河坊街", "120.1,30.2"), spot("钱塘江", "120.1,30.2")), new ArrayList<>());
        Map<String, Object> plan = service.plan("杭州", "景点", 2);
        assertFalse(plan.containsKey("error"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) plan.get("dayPlan");
        assertEquals(2, days.size());
        @SuppressWarnings("unchecked")
        List<Object> day1 = (List<Object>) days.get(0).get("schedule");
        assertEquals(3, day1.stream().filter(e -> "spot".equals(((Map<String, Object>) e).get("type"))).count());
        @SuppressWarnings("unchecked")
        List<Object> day2 = (List<Object>) days.get(1).get("schedule");
        assertEquals(1, day2.stream().filter(e -> "spot".equals(((Map<String, Object>) e).get("type"))).count());
    }

    // 乐园类关键词过滤：迪士尼/动物园不进行程
    @Test
    void plan_filtersBadSpots() {
        setUp(List.of(spot("西湖", "120.1,30.2"), spot("上海迪士尼", "120.1,30.2"),
                spot("野生动物园", "120.1,30.2")), new ArrayList<>());
        Map<String, Object> plan = service.plan("杭州", "景点", 1);
        assertFalse(plan.containsKey("error"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) plan.get("dayPlan");
        @SuppressWarnings("unchecked")
        List<Object> sched = (List<Object>) days.get(0).get("schedule");
        long spots = sched.stream().filter(e -> "spot".equals(((Map<String, Object>) e).get("type"))).count();
        assertEquals(1, spots);
        assertFalse(String.valueOf(sched).contains("迪士尼"));
        assertFalse(String.valueOf(sched).contains("动物园"));
    }

    // 午餐保护：12:00-13:30 插午餐，餐后景点时间 ≥13:30
    @Test
    void plan_insertsLunch_afterNoon() {
        List<Map<String, String>> spots = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            spots.add(spot("景点" + i, "120.1,30.2"));
        }
        setUp(spots, List.of(spot("外婆家", "120.1,30.2")));
        Map<String, Object> plan = service.plan("杭州", "景点", 1);
        assertFalse(plan.containsKey("error"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) plan.get("dayPlan");
        @SuppressWarnings("unchecked")
        List<Object> sched = (List<Object>) days.get(0).get("schedule");
        boolean lunchFound = sched.stream().anyMatch(e -> "lunch".equals(((Map<String, Object>) e).get("type")));
        assertTrue(lunchFound);
        // 午餐后第一个景点的开始时间 ≥ 13:30
        boolean seenLunch = false;
        for (Object e : sched) {
            Map<String, Object> entry = (Map<String, Object>) e;
            if ("lunch".equals(entry.get("type"))) {
                seenLunch = true;
                continue;
            }
            if (seenLunch && "spot".equals(entry.get("type"))) {
                String time = String.valueOf(entry.get("time"));
                assertTrue(time.compareTo("13:30") >= 0, "午餐后景点应 13:30 后开始，实际 " + time);
                break;
            }
        }
    }

    // 天气失败降级：不崩、行程仍在、weather 为 null
    @Test
    void plan_weatherFails_degradesGracefully() {
        setUp(List.of(spot("西湖", "120.1,30.2")), new ArrayList<>());
        when(weather.execute(any())).thenReturn(new SkillResult(false, "天气查询失败"));
        Map<String, Object> plan = service.plan("杭州", "景点", 1);
        assertFalse(plan.containsKey("error"));
        assertTrue(plan.containsKey("weather"));
    }

    // 缺目的地 → error
    @Test
    void plan_missingDestination_returnsError() {
        setUp(new ArrayList<>(), new ArrayList<>());
        assertTrue(service.plan(null, "景点", 1).containsKey("error"));
        assertTrue(service.plan("  ", "景点", 1).containsKey("error"));
    }

    // 高德 key 未配置 → error
    @Test
    void plan_noAmapKey_returnsError() {
        amap = mock(AmapPoiService.class);
        when(amap.hasApiKey()).thenReturn(false);
        service = new TravelPlanService(amap, mock(WeatherSkill.class));
        assertTrue(service.plan("杭州", "景点", 1).containsKey("error"));
    }

    // 周边+关键词搜索都无结果 → error
    @Test
    void plan_noSpotsFound_returnsError() {
        setUp(new ArrayList<>(), new ArrayList<>());
        Map<String, Object> plan = service.plan("杭州", "景点", 1);
        assertTrue(plan.containsKey("error"));
    }

    // TripPlanSkill 包装：结构化 → 可读文本含行程/预算；失败 → 话术
    @Test
    void tripPlanSkill_execute_returnsReadableText() {
        setUp(List.of(spot("西湖", "120.1,30.2")), new ArrayList<>());
        TripPlanSkill skill = new TripPlanSkill(service);
        SkillResult r = skill.execute(new SkillContext("u1", "{\"destination\":\"杭州\",\"days\":1}", ""));
        assertTrue(r.success());
        assertTrue(r.content().contains("杭州"));
        assertTrue(r.content().contains("西湖"));
        assertTrue(r.content().contains("预算参考"));
    }

    @Test
    void tripPlanSkill_missingDestination_returnsFriendlyMessage() {
        setUp(new ArrayList<>(), new ArrayList<>());
        TripPlanSkill skill = new TripPlanSkill(service);
        SkillResult r = skill.execute(new SkillContext("u1", "{}", ""));
        assertFalse(r.success());
        assertTrue(r.content().contains("目的地"));
    }
}