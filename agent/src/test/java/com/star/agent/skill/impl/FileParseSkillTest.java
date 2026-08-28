package com.star.agent.skill.impl;

import com.star.agent.skill.SkillResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// FileParseSkill：mock AnyDocService，验证 base64 入参透传与降级话术
class FileParseSkillTest {

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void 解析成功返回内容() {
        AnyDocService mock = new AnyDocService("node", "x.js", 1) {
            @Override
            public String parse(byte[] content, String filename) {
                return "识别出的文本内容";
            }
        };
        FileParseSkill skill = new FileParseSkill(mock);
        SkillResult r = skill.execute(new com.star.agent.skill.SkillContext("u1",
                "{\"filename\":\"简历.docx\",\"content_base64\":\"" + b64("abc") + "\"}", "t1"));
        assertTrue(r.success());
        assertTrue(r.content().contains("简历.docx"));
        assertTrue(r.content().contains("识别出的文本内容"));
    }

    @Test
    void 缺参数返回话术() {
        FileParseSkill skill = new FileParseSkill(new AnyDocService("node", "x.js", 1));
        SkillResult r = skill.execute(new com.star.agent.skill.SkillContext("u1", "{}", "t1"));
        assertFalse(r.success());
        assertTrue(r.content().contains("文件名"));
    }

    @Test
    void 非法base64返回话术() {
        FileParseSkill skill = new FileParseSkill(new AnyDocService("node", "x.js", 1));
        SkillResult r = skill.execute(new com.star.agent.skill.SkillContext("u1",
                "{\"filename\":\"a.pdf\",\"content_base64\":\"!!!不是base64!!!\"}", "t1"));
        assertFalse(r.success());
        assertTrue(r.content().contains("base64"));
    }

    @Test
    void 解析失败降级话术() {
        AnyDocService mock = new AnyDocService("node", "x.js", 1) {
            @Override
            public String parse(byte[] content, String filename) {
                return null;
            }
        };
        FileParseSkill skill = new FileParseSkill(mock);
        SkillResult r = skill.execute(new com.star.agent.skill.SkillContext("u1",
                "{\"filename\":\"a.docx\",\"content_base64\":\"" + b64("x") + "\"}", "t1"));
        assertFalse(r.success());
        assertTrue(r.content().contains("仅支持"));
    }
}