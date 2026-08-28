package com.star.agent.skill.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.star.agent.skill.JsonSchemaBuilder;
import com.star.agent.skill.RiskLevel;
import com.star.agent.skill.Skill;
import com.star.agent.skill.SkillContext;
import com.star.agent.skill.SkillMeta;
import com.star.agent.skill.SkillResult;
import com.star.agent.skill.SkillType;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.List;

// 文件识别技能：file_parse（EXT 层，独立调用）——把文档（pdf/word/excel/ppt/csv 等）内容解析成文本
// 入参走 base64（与 rag_upload 同通道约定），解析由 AnyDocService 子进程完成；失败返回话术不抛异常
@Component
public class FileParseSkill implements Skill {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AnyDocService anyDocService;

    public FileParseSkill(AnyDocService anyDocService) {
        this.anyDocService = anyDocService;
    }

    @Override
    public SkillMeta meta() {
        return new SkillMeta(
                "file_parse",
                "解析文档内容为文本（支持 pdf/word/excel/ppt/csv/odt/rtf/epub/txt/md），输入文件名和 base64 编码的文件内容",
                JsonSchemaBuilder.object()
                        .addString("filename", "文件名（含扩展名），如 简历.docx", true)
                        .addString("content_base64", "文件内容 Base64 编码", true)
                        .build(),
                RiskLevel.LOW,
                List.of("CHAT", "OFFICE", "DATA"),
                "LOCAL",
                SkillType.EXT
        );
    }

    @Override
    public SkillResult execute(SkillContext ctx) {
        try {
            JsonNode node = mapper.readTree(ctx.args());
            String filename = node.path("filename").asText();
            String contentBase64 = node.path("content_base64").asText();
            if (filename.isEmpty() || contentBase64.isEmpty()) {
                return new SkillResult(false, "请提供文件名和文件内容（base64）");
            }
            byte[] content = Base64.getDecoder().decode(contentBase64);
            String text = anyDocService.parse(content, filename);
            if (text == null) {
                return new SkillResult(false, "无法识别该文件（仅支持 pdf/word/excel/ppt/csv/odt/rtf/epub/txt/md）");
            }
            return new SkillResult(true, "已解析「" + filename + "」，内容如下：\n" + text);
        } catch (IllegalArgumentException e) {
            return new SkillResult(false, "文件内容不是合法的 base64");
        } catch (Exception e) {
            return new SkillResult(false, "文件解析失败: " + e.getMessage());
        }
    }
}