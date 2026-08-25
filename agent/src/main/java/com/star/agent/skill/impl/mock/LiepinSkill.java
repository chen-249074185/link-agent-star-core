package com.star.agent.skill.impl.mock;

import com.star.agent.skill.JsonSchemaBuilder;
import com.star.agent.skill.RiskLevel;
import com.star.agent.skill.Skill;
import com.star.agent.skill.SkillContext;
import com.star.agent.skill.SkillMeta;
import com.star.agent.skill.SkillResult;
import com.star.agent.skill.SkillType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

// 猎聘片区就业分析 Mock（MAIN 主线：Workflow 第一步，评估片区就业热度；后续换真实猎聘 MCP）
@Component
@ConditionalOnProperty(name = "linkagent.workflow.mock-enabled", havingValue = "true")
public class LiepinSkill implements Skill {

    @Override
    public SkillMeta meta() {
        return new SkillMeta(
                "liepin_jobs",
                "分析指定片区/行业的岗位热度与薪资水平（Mock 假数据）",
                JsonSchemaBuilder.object()
                        .addString("city", "城市，如 北京", true)
                        .addString("jobKeyword", "岗位关键词，如 Java（选填）", false)
                        .build(),
                RiskLevel.LOW,
                List.of("CHAT", "DATA"),
                "LOCAL",
                SkillType.MAIN
        );
    }

    @Override
    public SkillResult execute(SkillContext ctx) {
        // 假数据：岗位数 jobs / 平均薪资 avgSalary / 就业热度 hot（Workflow 报告要用）
        return new SkillResult(true,
                "{\"city\":\"北京\",\"jobs\":3200,\"avgSalary\":\"18000-25000\",\"hot\":\"高\"}");
    }
}