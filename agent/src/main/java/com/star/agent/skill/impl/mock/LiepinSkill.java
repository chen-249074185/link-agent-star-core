package com.star.agent.skill.impl.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.star.agent.skill.JsonSchemaBuilder;
import com.star.agent.skill.RiskLevel;
import com.star.agent.skill.Skill;
import com.star.agent.skill.SkillContext;
import com.star.agent.skill.SkillMeta;
import com.star.agent.skill.SkillResult;
import com.star.agent.skill.SkillType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

// 猎聘片区就业分析 Mock（MAIN 主线：Workflow 第一步，评估片区就业热度；后续换真实猎聘 MCP）
// 资料库 = rag_data/jobs/{城市}.txt 数据文件（参照夏令营 rag_data 目录方式，不在代码里硬编码数据）：
//   每城市一个文件，内容为就业 JSON 对象（city/jobs/avgSalary/hot）
@Component
@ConditionalOnProperty(name = "linkagent.workflow.mock-enabled", havingValue = "true")
public class LiepinSkill implements Skill {

    private static final Logger logger = LoggerFactory.getLogger(LiepinSkill.class);
    private static final Path JOBS_DIR = Paths.get("rag_data", "jobs");

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public SkillMeta meta() {
        return new SkillMeta(
                "liepin_jobs",
                "分析指定片区/行业的岗位热度与薪资水平（Mock：读 rag_data/jobs/{城市}.txt 资料库）",
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
        String city = "北京";   // 参数缺省兜底，保证 Workflow 必有一段就业分析
        try {
            JsonNode node = mapper.readTree(ctx.args());
            if (node.has("city") && !node.path("city").asText().isBlank()) {
                city = node.path("city").asText();
            }
        } catch (Exception e) {
            // 参数解析失败用默认城市（报告不能因参数问题缺块）
        }
        Path file = JOBS_DIR.resolve(city + ".txt");
        if (!Files.exists(file)) {
            return new SkillResult(false, "「" + city + "」就业数据暂未收录（资料库缺 " + file + "）");
        }
        try {
            return new SkillResult(true, Files.readString(file, StandardCharsets.UTF_8).trim());
        } catch (IOException e) {
            logger.warn("读就业资料库失败: {}", file, e);
            return new SkillResult(false, "「" + city + "」就业数据读取失败，请稍后再试");
        }
    }
}