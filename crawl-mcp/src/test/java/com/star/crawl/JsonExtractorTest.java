package com.star.crawl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// JsonExtractor 测试：ld+json 优先 / script 内平衡括号兜底（字符串字面量感知）
class JsonExtractorTest {

    private final JsonExtractor extractor = new JsonExtractor();

    // ① 优先：ld+json 脚本块
    @Test
    void extractsLdJson() {
        String html = "<html><head>"
                + "<script type=\"application/ld+json\">{\"@type\":\"Apartment\",\"price\":6500,\"city\":\"北京\"}</script>"
                + "</head><body>页面正文</body></html>";

        String json = extractor.extractJson(html);

        assertTrue(json.contains("\"city\":\"北京\""), "应挖出 ld+json，实际: " + json);
    }

    // ② 兜底：window.XXX = {...} 内嵌状态（含字符串里的花括号也不误判）
    @Test
    void extractsBalancedBracesFromScript() {
        String html = "<script>window.__INITIAL_STATE__ = {\"title\":\"房源信息\",\"note\":\"大括号{测试}没影响\",\"list\":[{\"name\":\"回龙观一居\"}]};</script>";

        String json = extractor.extractJson(html);

        assertTrue(json.startsWith("{"));
        assertTrue(json.endsWith("}"));
        assertTrue(json.contains("\"list\""), "应挖出完整 JSON 对象，实际: " + json);
        assertTrue(json.contains("大括号{测试}没影响"), "字符串内的花括号不应提前截断");
    }

    // 无 JSON → null（调用方降级）
    @Test
    void noJson_returnsNull() {
        assertNull(extractor.extractJson("<html><body>纯文本页面没有任何脚本数据</body></html>"));
        assertNull(extractor.extractJson(""));
        assertNull(extractor.extractJson(null));
    }
}