package com.star.agent.infra.web;

import com.star.agent.infra.web.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// 管理端占位（鉴权双轨之管理轨端点打底，W12 10-27）：
//   GET /api/admin/health = 管理端连通性自检（X-Admin-Token 校验通过才能到这）
// 10-30 管理页重写时在此扩展 RAG 管理/技能开关/调用统计/日志审计四 Tab 端点
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        return Result.ok(Map.of("status", "ok"));
    }
}