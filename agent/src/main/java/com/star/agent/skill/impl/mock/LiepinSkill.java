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

// 猎聘片区就业分析 Mock（MAIN 主线：Workflow 第一步，评估片区就业热度）
// 注意：岗位/就业数据是【用户私有】（真实模式 = liepin-mcp 用用户自己的 Token 查真实猎聘），
// 这里只放一条演示占位假数据跑通 Workflow，绝不建"共享 job 库"
@Component
@ConditionalOnProperty(name = "linkagent.workflow.mock-enabled", havingValue = "true")
public class LiepinSkill implements Skill {

    @Override
    public SkillMeta meta() {
        return new SkillMeta(
                "liepin_jobs",
                "分析指定片区/行业的岗位热度与薪资水平（Mock 演示占位，真实数据走用户猎聘账号）",
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
        return new SkillResult(true,
                "{\"city\":\"演示城市\",\"jobs\":1000,\"avgSalary\":\"15000-20000\",\"hot\":\"中\"}");
    }
}