package com.star.agent.infra.web;

import com.star.agent.mcp.McpConnection;
import com.star.agent.skill.Skill;
import com.star.agent.skill.SkillMeta;
import com.star.agent.skill.SkillRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// 管理端（鉴权双轨之管理轨，X-Admin-Token 保护， 四 Tab 落地）：
//   GET  /api/admin/health                 → 连通性自检
//   GET  /api/admin/skills                 → 技能清单（含停用状态）【技能开关 Tab】
//   POST /api/admin/skills/{name}/toggle   → 停用/启用技能
//   GET  /api/admin/stats                  → 调用统计快照【调用统计 Tab】
//   GET  /api/admin/logs?q=&max=           → 日志检索（traceId 可查）【日志审计 Tab】
//   GET  /api/admin/rag/kbs                → 全用户知识库清单【RAG 管理 Tab】
//   DELETE /api/admin/rag/kb?userId=&kbId= → 超管删除任意知识库
//   GET  /api/admin/rag/files?userId=&kbId= → 某库内文件清单（打开库看文件）
//   DELETE /api/admin/rag/file?userId=&kbId=&filename= → 超管删除库内单个文件
@Tag(name = "管理端", description = "超管视角（X-Admin-Token 凭证）：技能开关/调用统计/日志审计/RAG 全库管理")
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final SkillRegistry skillRegistry;
    private final AdminSkillSwitch skillSwitch;
    private final UsageStatsService usageStats;
    private final LogTailService logTail;
    private final Optional<McpConnection> ragConnection;   // mock=true 时无 rag-mcp bean → 503 降级

    public AdminController(SkillRegistry skillRegistry, AdminSkillSwitch skillSwitch,
                           UsageStatsService usageStats, LogTailService logTail,
                           Optional<McpConnection> ragConnection) {
        this.skillRegistry = skillRegistry;
        this.skillSwitch = skillSwitch;
        this.usageStats = usageStats;
        this.logTail = logTail;
        this.ragConnection = ragConnection;
    }

    @Operation(summary = "连通性自检")
    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        return Result.ok(Map.of("status", "ok"));
    }

    // 技能清单：花名册全部技能 + 停用状态（SkillType 分层标注，管理页可一键开关）
    @Operation(summary = "技能清单", description = "全部技能 + 停用状态 + 三层划分标注")
    @GetMapping("/skills")
    public Result<List<Map<String, Object>>> skills() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Skill skill : skillRegistry.allSkills()) {
            SkillMeta meta = skill.meta();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", meta.name());
            row.put("description", meta.description());
            row.put("skillType", meta.skillType().name());
            row.put("riskLevel", meta.riskLevel().name());
            row.put("provider", meta.provider());
            row.put("scenes", meta.scenes());
            row.put("disabled", skillSwitch.isDisabled(meta.name()));
            list.add(row);
        }
        return Result.ok(list);
    }

    // 停用/启用技能：翻转状态返回新状态；治理内核 check() 对停用技能直接拒
    @Operation(summary = "停用/启用技能")
    @PostMapping("/skills/{name}/toggle")
    public Result<Map<String, Object>> toggleSkill(@PathVariable String name) {
        Skill skill = skillRegistry.getSkill(name);
        if (skill == null) {
            return Result.fail(404, "技能不存在: " + name);
        }
        boolean disabled = !skillSwitch.isDisabled(name);
        skillSwitch.setDisabled(name, disabled);
        return Result.ok(Map.of("name", name, "disabled", disabled));
    }

    // 调用统计快照：技能调用次数 / 各场景消息数 / 工作流执行 / 估算 token
    @Operation(summary = "调用统计快照")
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        return Result.ok(usageStats.snapshot());
    }

    // 日志审计：按关键词（可输 traceId/技能名/ERROR）过滤最近日志行
    @Operation(summary = "日志检索", description = "按关键词（traceId/技能名/ERROR）过滤最近日志")
    @GetMapping("/logs")
    public Result<List<String>> logs(@RequestParam(required = false) String q,
                                     @RequestParam(defaultValue = "200") int max) {
        return Result.ok(logTail.tail(q, Math.min(Math.max(max, 1), 1000)));
    }

    // RAG 管理：全用户知识库清单（rag_admin_list_all，超管视角不做归属过滤）
    @Operation(summary = "全用户知识库清单")
    @GetMapping("/rag/kbs")
    public Result<String> ragKbs() {
        return ragCall("rag_admin_list_all", Map.of());
    }

    // RAG 管理：超管删除任意用户的知识库（rag_admin_delete_kb，跳过归属校验 + 审计日志）
    @Operation(summary = "删除任意知识库")
    @DeleteMapping("/rag/kb")
    public Result<String> ragDeleteKb(@RequestParam String userId, @RequestParam String kbId) {
        return ragCall("rag_admin_delete_kb", Map.of("user_id", userId, "kb_id", kbId));
    }

    // RAG 管理：某库内文件清单（rag_admin_list_files，打开库看文件用）
    @Operation(summary = "库内文件清单")
    @GetMapping("/rag/files")
    public Result<String> ragFiles(@RequestParam String userId, @RequestParam String kbId) {
        return ragCall("rag_admin_list_files", Map.of("user_id", userId, "kb_id", kbId));
    }

    // RAG 管理：超管删除库内单个文件（rag_admin_delete_file，跳过归属校验 + 审计日志）
    @Operation(summary = "删除库内单个文件")
    @DeleteMapping("/rag/file")
    public Result<String> ragDeleteFile(@RequestParam String userId, @RequestParam String kbId,
                                        @RequestParam String filename) {
        return ragCall("rag_admin_delete_file", Map.of("user_id", userId, "kb_id", kbId, "filename", filename));
    }

    private Result<String> ragCall(String tool, Map<String, Object> args) {
        if (ragConnection.isEmpty()) {
            return Result.fail(503, "rag-mcp 未连接（mock 模式或子进程未拉起），管理端 RAG 功能不可用");
        }
        try {
            return Result.ok(ragConnection.get().callTool(tool, args));
        } catch (Exception e) {
            return Result.fail(500, "rag-mcp 调用失败: " + e.getMessage());
        }
    }
}