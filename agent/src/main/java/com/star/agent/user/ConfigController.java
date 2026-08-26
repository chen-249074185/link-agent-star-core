package com.star.agent.user;

import com.star.agent.infra.web.Result;
import com.star.agent.user.domain.UserConfigDo;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

// 用户配置接口（Web/小程序"我的配置"页按钮的后端）：
//   POST   /api/config/liepin-token?userId&token  = 设置/更新猎聘 Token（微信粘贴保存走同一仓库）
//   DELETE /api/config/liepin-token?userId         = 删除配置（不再使用猎聘）
//   GET    /api/config/liepin-token?userId         = 配置状态（是否已配置+更新时间，不返回 Token 明文）
// 全部绑定 userId（W12 换 JWT 后从会话取，签名不变）
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final UserConfigRepository repository;

    public ConfigController(UserConfigRepository repository) {
        this.repository = repository;
    }

    @PostMapping("/liepin-token")
    public Result<Map<String, Object>> saveToken(@RequestParam("userId") String userId,
                                                 @RequestParam("token") String token) {
        if (token == null || token.isBlank()) {
            return Result.fail(400, "Token 不能为空");
        }
        repository.saveLiepinToken(userId, token);
        return Result.ok(Map.of("userId", userId, "configured", true));
    }

    @DeleteMapping("/liepin-token")
    public Result<Map<String, Object>> deleteToken(@RequestParam("userId") String userId) {
        repository.deleteLiepinToken(userId);
        return Result.ok(Map.of("userId", userId, "configured", false));
    }

    @GetMapping("/liepin-token")
    public Result<Map<String, Object>> status(@RequestParam("userId") String userId) {
        UserConfigDo config = repository.findByUserId(userId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", userId);
        data.put("configured", config != null && config.getLiepinToken() != null);
        data.put("updatedAt", config == null ? null : String.valueOf(config.getUpdatedAt()));   // Map.of 不能放 null
        return Result.ok(data);
    }
}