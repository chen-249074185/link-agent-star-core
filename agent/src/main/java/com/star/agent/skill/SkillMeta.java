package com.star.agent.skill;

import java.util.List;
import java.util.Map;

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
