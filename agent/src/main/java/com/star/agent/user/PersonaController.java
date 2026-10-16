package com.star.agent.user;

import com.star.agent.infra.web.AuthContext;
import com.star.agent.infra.web.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

// 人设接口：
//   GET    /api/personas             → 内置预设列表 [{id,name}]（快捷项）
//   GET    /api/user/persona         → 当前人设文本（null=未设置，走默认助手）
//   PUT    /api/user/persona?persona=xx → 设置/覆盖自定义人设（预设 id 也可直接传）
//   DELETE /api/user/persona         → 清除人设（恢复默认）
// 全部 JWT 鉴权，userId 从 AuthContext 取（不信任参数）
@Tag(name = "助手人设", description = "内置预设/自定义人设（≤200 字，持久化），需 JWT")
@RestController
@RequestMapping("/api")
public class PersonaController {

    private final PersonaManager personaManager;

    public PersonaController(PersonaManager personaManager) {
        this.personaManager = personaManager;
    }

    // 内置预设列表（快捷项，不含 prompt 细节）
    @Operation(summary = "内置人设预设列表")
    @GetMapping("/personas")
    public Result<List<Map<String, String>>> presets() {
        return Result.ok(personaManager.presets());
    }

    // 当前人设（null=未设置）
    @GetMapping("/user/persona")
    public Result<Map<String, Object>> current(HttpServletRequest request) {
        String userId = AuthContext.userId(request);
        String persona = personaManager.personaOf(userId);
        return Result.ok(Map.of(
                "userId", userId,
                "persona", persona == null ? "" : persona,
                "configured", persona != null && !persona.isBlank()));
    }

    // 设置/覆盖人设：persona 参数可以是预设 id（转预设 prompt）或自定义自由文本
    @Operation(summary = "设置/覆盖人设", description = "预设 id 或自定义文本（≤200 字）")
    @PutMapping("/user/persona")
    public Result<Map<String, Object>> save(HttpServletRequest request, @RequestParam String persona) {
        String text = persona.trim();
        // 预设 id 快捷转换
        String preset = personaManager.presetPrompt(text);
        if (preset != null) {
            text = preset;
        }
        if (text.isEmpty()) {
            return Result.fail(400, "人设不能为空");
        }
        if (text.length() > PersonaManager.MAX_PERSONA_LEN) {
            return Result.fail(400, "人设过长（最多 " + PersonaManager.MAX_PERSONA_LEN + " 字）");
        }
        String userId = AuthContext.userId(request);
        personaManager.save(userId, text);
        return Result.ok(Map.of("userId", userId, "configured", true));
    }

    // 清除人设（恢复默认）
    @DeleteMapping("/user/persona")
    public Result<Map<String, Object>> clear(HttpServletRequest request) {
        String userId = AuthContext.userId(request);
        personaManager.save(userId, null);
        return Result.ok(Map.of("userId", userId, "configured", false));
    }
}