package com.star.agent.travel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.star.agent.infra.web.AuthContext;
import com.star.agent.infra.web.Result;
import com.star.agent.travel.dao.TravelLogDao;
import com.star.agent.travel.domain.TravelLogDo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 旅游功能：
//   POST /api/travel/plan      {"destination","days","keyword"} → 行程 JSON（TripPlanService 编排）
//   POST /api/travel/upload    multipart file → {url} 图片上传（本地 travel-images/ 存储）
//   POST /api/travel/log       {"province","city","locationName","lng","lat","content","imgList","travelDate"} → 发布游记
//   GET  /api/travel/logs      → 我的游记列表（时间倒序）
//   DELETE /api/travel/log/{id} → 删除游记（省份游记清零自动熄灭）
//   GET  /api/travel/footprints → 点亮地图足迹点（游记 → 坐标，缺失回退省级行政中心）
//   GET  /api/map/config       → 高德 JS key + 安全密钥（前端地图用，不从 HTML 硬编码）
@Tag(name = "旅游", description = "旅游规划/游记/点亮地图（JWT 鉴权，图片走本地存储）")
@RestController
@RequestMapping("/api/travel")
public class TravelController {

    private static final Logger logger = LoggerFactory.getLogger(TravelController.class);

    // 省级行政中心坐标（游记无坐标时回退用，覆盖 34 省级行政区）
    private static final Map<String, double[]> PROVINCE_COORDS = new LinkedHashMap<>();

    static {
        PROVINCE_COORDS.put("北京", new double[]{116.405285, 39.904989});
        PROVINCE_COORDS.put("天津", new double[]{117.190182, 39.125596});
        PROVINCE_COORDS.put("河北", new double[]{114.502461, 38.045474});
        PROVINCE_COORDS.put("山西", new double[]{112.549248, 37.857014});
        PROVINCE_COORDS.put("内蒙古", new double[]{111.670801, 40.818311});
        PROVINCE_COORDS.put("辽宁", new double[]{123.429096, 41.796767});
        PROVINCE_COORDS.put("吉林", new double[]{125.324500, 43.886841});
        PROVINCE_COORDS.put("黑龙江", new double[]{126.642464, 45.756967});
        PROVINCE_COORDS.put("上海", new double[]{121.472644, 31.231706});
        PROVINCE_COORDS.put("江苏", new double[]{118.767413, 32.041544});
        PROVINCE_COORDS.put("浙江", new double[]{120.153576, 30.287459});
        PROVINCE_COORDS.put("安徽", new double[]{117.283042, 31.861190});
        PROVINCE_COORDS.put("福建", new double[]{119.306239, 26.075302});
        PROVINCE_COORDS.put("江西", new double[]{115.892151, 28.676493});
        PROVINCE_COORDS.put("山东", new double[]{117.000923, 36.675807});
        PROVINCE_COORDS.put("河南", new double[]{113.665412, 34.757975});
        PROVINCE_COORDS.put("湖北", new double[]{114.298572, 30.584355});
        PROVINCE_COORDS.put("湖南", new double[]{112.982279, 28.194090});
        PROVINCE_COORDS.put("广东", new double[]{113.280637, 23.125178});
        PROVINCE_COORDS.put("广西", new double[]{108.320004, 22.824020});
        PROVINCE_COORDS.put("海南", new double[]{110.331190, 20.031971});
        PROVINCE_COORDS.put("重庆", new double[]{106.504962, 29.533155});
        PROVINCE_COORDS.put("四川", new double[]{104.065735, 30.659462});
        PROVINCE_COORDS.put("贵州", new double[]{106.713478, 26.578343});
        PROVINCE_COORDS.put("云南", new double[]{102.712251, 25.040609});
        PROVINCE_COORDS.put("西藏", new double[]{91.132212, 29.660361});
        PROVINCE_COORDS.put("陕西", new double[]{108.948024, 34.263161});
        PROVINCE_COORDS.put("甘肃", new double[]{103.823557, 36.058039});
        PROVINCE_COORDS.put("青海", new double[]{101.778916, 36.623178});
        PROVINCE_COORDS.put("宁夏", new double[]{106.278179, 38.466370});
        PROVINCE_COORDS.put("新疆", new double[]{87.617733, 43.792818});
        PROVINCE_COORDS.put("台湾", new double[]{121.509062, 25.044332});
        PROVINCE_COORDS.put("香港", new double[]{114.173355, 22.320048});
        PROVINCE_COORDS.put("澳门", new double[]{113.549090, 22.198951});
    }

    private final TravelPlanService planService;
    private final TravelLogDao travelLogDao;
    private final TravelImageStore imageStore;
    private final com.star.agent.infra.config.AppConfig appConfig;
    private final ObjectMapper mapper = new ObjectMapper();

    public TravelController(TravelPlanService planService, TravelLogDao travelLogDao,
                            TravelImageStore imageStore, com.star.agent.infra.config.AppConfig appConfig) {
        this.planService = planService;
        this.travelLogDao = travelLogDao;
        this.imageStore = imageStore;
        this.appConfig = appConfig;
    }

    // 旅游规划（页面调）：目的地 + 天数 + 关键词 + 预算（元/天，选填）→ 结构化行程 JSON
    @Operation(summary = "旅游规划", description = "目的地→行程 JSON（景点/午餐/美食/天气/预算，导航链接；budget 选填，null=无上限）")
    @PostMapping("/plan")
    public Result<Map<String, Object>> plan(@RequestBody Map<String, Object> body) {
        String destination = body.get("destination") == null ? null : String.valueOf(body.get("destination"));
        String keyword = body.get("keyword") == null ? null : String.valueOf(body.get("keyword"));
        Integer days = body.get("days") == null ? null
                : Integer.valueOf(String.valueOf(body.get("days")));
        Integer budget = body.get("budget") == null ? null : parsePositiveInt(String.valueOf(body.get("budget")));
        Map<String, Object> plan = planService.plan(destination, keyword, days, budget);
        if (plan.containsKey("error")) {
            return Result.fail(400, String.valueOf(plan.get("error")));
        }
        return Result.ok(plan);
    }

    // 预算解析：正整数；非法/非正数 → null（=无上限）
    private Integer parsePositiveInt(String s) {
        try {
            int v = Integer.parseInt(s.trim());
            return v > 0 ? v : null;
        } catch (Exception e) {
            return null;
        }
    }

    // 图片上传：multipart → 本地存储 → {url}
    @Operation(summary = "游记图片上传", description = "返回 {url}（/travel-images/xxx，10MB 上限 jpg/png/webp/gif）")
    @PostMapping("/upload")
    public Result<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return Result.fail(400, "请选择图片");
        }
        try {
            String url = imageStore.save(file.getBytes(), file.getOriginalFilename());
            return Result.ok(Map.of("url", url));
        } catch (IllegalArgumentException e) {
            return Result.fail(400, e.getMessage());
        } catch (Exception e) {
            logger.warn("游记图片上传失败: {}", e.getMessage());
            return Result.fail(500, "图片上传失败，请稍后再试");
        }
    }

    // 发布游记（点亮地图的数据源：发布 → 足迹点 → 省份点亮）
    @Operation(summary = "发布游记", description = "省份必填；坐标缺失回退省级行政中心")
    @PostMapping("/log")
    public Result<Map<String, Object>> publish(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String userId = AuthContext.userId(request);
        String province = trimToNull(body.get("province"));
        if (province == null) {
            return Result.fail(400, "省份不能为空");
        }
        String content = trimToNull(body.get("content"));
        if (content == null) {
            return Result.fail(400, "游记内容不能为空");
        }
        TravelLogDo log = new TravelLogDo();
        log.setUserId(userId);
        log.setProvince(province);
        log.setCity(trimToNull(body.get("city")));
        log.setLocationName(trimToNull(body.get("locationName")));
        log.setLng(parseDouble(body.get("lng")));
        log.setLat(parseDouble(body.get("lat")));
        log.setContent(content);
        log.setImgList(body.get("imgList") == null ? null : String.valueOf(body.get("imgList")));
        log.setTravelDate(parseDate(body.get("travelDate")));
        travelLogDao.insert(log);
        return Result.ok(Map.of("id", log.getId()));
    }

    // 我的游记列表（时间倒序）
    @Operation(summary = "游记列表")
    @GetMapping("/logs")
    public Result<List<Map<String, Object>>> logs(HttpServletRequest request) {
        String userId = AuthContext.userId(request);
        List<Map<String, Object>> list = new ArrayList<>();
        for (TravelLogDo log : travelLogDao.findByUserId(userId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", log.getId());
            row.put("province", log.getProvince());
            row.put("city", log.getCity());
            row.put("locationName", log.getLocationName());
            row.put("content", log.getContent());
            row.put("imgList", log.getImgList());
            row.put("travelDate", log.getTravelDate() == null ? null : log.getTravelDate().toString());
            row.put("createdAt", log.getCreatedAt() == null ? null : log.getCreatedAt().toString());
            list.add(row);
        }
        return Result.ok(list);
    }

    // 删除游记（省份游记清零 → 地图熄灭）
    @Operation(summary = "删除游记")
    @DeleteMapping("/log/{id}")
    public Result<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        String userId = AuthContext.userId(request);
        TravelLogDo log = travelLogDao.findByIdAndUserId(id, userId);
        if (log == null) {
            return Result.fail(404, "游记不存在");
        }
        travelLogDao.deleteByIdAndUserId(id, userId);
        if (log.getImgList() != null && !log.getImgList().isBlank()) {
            try {
                for (Object url : mapper.readValue(log.getImgList(), List.class)) {
                    imageStore.deleteIfExists(String.valueOf(url));
                }
            } catch (Exception ignored) {
            }
        }
        return Result.ok(null);
    }

    // 点亮地图足迹点：游记 → {name, coordinates, province, description, date, categories}（前端射线法点亮省份）
    @Operation(summary = "足迹点（点亮地图）", description = "坐标优先游记 lng/lat，缺失回退省级行政中心表")
    @GetMapping("/footprints")
    public Result<List<Map<String, Object>>> footprints(HttpServletRequest request) {
        String userId = AuthContext.userId(request);
        List<Map<String, Object>> locations = new ArrayList<>();
        int i = 0;
        for (TravelLogDo log : travelLogDao.findByUserId(userId)) {
            String province = normalizeProvince(log.getProvince());
            double[] coord = null;
            if (log.getLng() != null && log.getLat() != null) {
                coord = new double[]{log.getLng(), log.getLat()};
            }
            if (coord == null) {
                coord = PROVINCE_COORDS.get(province);
            }
            if (coord == null) {
                continue;
            }
            String locationName = log.getLocationName() == null ? "" : log.getLocationName().trim();
            String city = log.getCity() == null ? "" : log.getCity().trim();
            String district = "";
            Map<String, Object> loc = new LinkedHashMap<>();
            loc.put("name", locationName.isEmpty() ? (province + (city.isEmpty() ? "" : "·" + city)) : locationName);
            loc.put("coordinates", coord[0] + "," + coord[1]);
            loc.put("province", province);
            loc.put("description", log.getContent() == null ? "" : log.getContent());
            String date = log.getTravelDate() == null ? "" : log.getTravelDate().toString();
            loc.put("date", date.isEmpty() && log.getCreatedAt() != null
                    ? log.getCreatedAt().toLocalDate().toString() : date);
            loc.put("categories", List.of("旅行"));
            loc.put("markerPreset", MARKER_PRESETS[i % MARKER_PRESETS.length]);
            loc.put("photos", parseImgList(log.getImgList()));
            locations.add(loc);
            i++;
        }
        return Result.ok(locations);
    }

    // 高德 JS API 配置下发（前端地图用；Web端(JS API)类型 key，与 Web 服务 key 不同）
    @Operation(summary = "高德 JS key 下发", description = "地图初始化用，不从 HTML 硬编码")
    @GetMapping("/map-config")
    public Result<Map<String, String>> mapConfig() {
        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("amapKey", appConfig.amapJsKey());
        cfg.put("amapSecurityJsCode", appConfig.amapJsSecurityCode());
        return Result.ok(cfg);
    }

    private static final String[] MARKER_PRESETS = {"sunset", "ocean", "violet", "forest", "amber", "citrus"};

    private List<String> parseImgList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            Object parsed = mapper.readValue(raw, List.class);
            if (parsed instanceof List<?> items) {
                return items.stream().map(String::valueOf).toList();
            }
        } catch (Exception ignored) {
        }
        return List.of();
    }

    private String normalizeProvince(String name) {
        if (name == null) {
            return "";
        }
        String s = name.trim();
        s = s.replace("维吾尔自治区", "").replace("壮族自治区", "").replace("回族自治区", "")
                .replace("自治区", "").replace("特别行政区", "").replace("省", "").replace("市", "");
        return s;
    }

    private String trimToNull(Object o) {
        if (o == null) {
            return null;
        }
        String v = String.valueOf(o).trim();
        return v.isEmpty() ? null : v;
    }

    private Double parseDouble(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return Double.valueOf(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LocalDate parseDate(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return LocalDate.parse(String.valueOf(o).trim());
        } catch (Exception e) {
            return null;
        }
    }
}