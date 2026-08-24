package com.star.agent.workflow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

// HouseTextParser 测试：Mock JSON 透传 / 真实 RAG 文本解析 / 无房源空数组
class HouseTextParserTest {

    // Mock 数据（结构化 JSON）→ 原样透传不二次解析
    @Test
    void jsonPassesThrough() {
        String json = "[{\"name\":\"海淀学院路两居\",\"price\":6500}]";
        assertTrue(HouseTextParser.toJson(json).equals(json));
    }

    // 真实 RAG 文本（[score=..] 前缀 + 自由文本）→ 提取结构化字段
    @Test
    void ragTextExtractsHouses() {
        String rag = "[score=0.719] 北京海淀学院路两居室月租6500元，通勤中关村35分钟。\n"
                + "[score=0.730] 北京朝阳望京单间月租3800元，通勤中关村50分钟。";

        String json = HouseTextParser.toJson(rag);

        assertTrue(json.contains("\"price\":6500"), "应提取价格，实际: " + json);
        assertTrue(json.contains("\"commute\":35"), "应提取通勤，实际: " + json);
        assertTrue(json.contains("海淀学院路"), "应提取房源名，实际: " + json);
        assertTrue(json.startsWith("[") && json.endsWith("]"));
    }

    // 无价格信息的行跳过；空输入 → []
    @Test
    void nonHouseLinesSkipped() {
        assertTrue(HouseTextParser.toJson("[score=0.9] 这是没有价格的废话文本。").equals("[]"));
        assertTrue(HouseTextParser.toJson(null).equals("[]"));
        assertTrue(HouseTextParser.toJson("").equals("[]"));
    }
}