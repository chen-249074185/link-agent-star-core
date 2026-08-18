package com.star.agent.skill;

import java.util.List;
import java.util.Map;

// 技能的完整说明书：name/description/parameters 给 LLM 看（能不能调、怎么调），
// riskLevel/scenes/skillType 给治理内核看（要不要确认、能不能进 Workflow），provider 标明来源（本地/MCP）
public record SkillMeta(
        String name,
        String description,
        Map<String, Object> parameters,
        RiskLevel riskLevel,
        List<String> scenes,
        String provider,       // "LOCAL" 或 "MCP:server名"
        SkillType skillType    // MAIN / SCENE / EXT
) {
}
