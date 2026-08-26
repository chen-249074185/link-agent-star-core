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

// RAG 房源检索 Mock（MAIN 主线：Workflow 房源筛选输入源；mock 先行，后续换真实 RAG MCP）
// 资料库 = rag_data/houses/{城市}.txt 数据文件（参照夏令营 rag_data 目录方式，不在代码里硬编码数据）：
//   每城市一个文件，内容为房源 JSON 数组（字段与真实版对齐 name/area/price/commute/rating），
//   新增城市/改房源 = 加文件/改文件，代码零改动
@Component
@ConditionalOnProperty(name = "linkagent.workflow.mock-enabled", havingValue = "true")
public class RagSearchSkill implements Skill {

    private static final Logger logger = LoggerFactory.getLogger(RagSearchSkill.class);
    private static final Path HOUSES_DIR = Paths.get("rag_data", "houses");

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public SkillMeta meta() {
        return new SkillMeta(
                "rag_search",
                "检索指定城市符合条件的房源列表（Mock：读 rag_data/houses/{城市}.txt 资料库）",
                JsonSchemaBuilder.object()
                        .addString("city", "城市，如 北京", true)
                        .addInteger("maxPrice", "预算上限，元/月", false)
                        .build(),
                RiskLevel.LOW,
                List.of("CHAT", "DATA"),
                "LOCAL",
                SkillType.MAIN
        );
    }

    @Override
    public SkillResult execute(SkillContext ctx) {
        String city = "北京";   // 参数缺省兜底，保证 Workflow 必有一段房源
        try {
            JsonNode node = mapper.readTree(ctx.args());
            if (node.has("city") && !node.path("city").asText().isBlank()) {
                city = node.path("city").asText();
            }
        } catch (Exception e) {
            // 参数解析失败用默认城市（报告不能因参数问题缺块）
        }
        Path file = HOUSES_DIR.resolve(city + ".txt");
        if (!Files.exists(file)) {
            return new SkillResult(false, "「" + city + "」房源数据暂未收录（资料库缺 " + file + "）");
        }
        try {
            return new SkillResult(true, Files.readString(file, StandardCharsets.UTF_8).trim());
        } catch (IOException e) {
            logger.warn("读房源资料库失败: {}", file, e);
            return new SkillResult(false, "「" + city + "」房源数据读取失败，请稍后再试");
        }
    }
}