package com.star.agent.mcp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 链家解析器单测：真实页面样本（src/test/resources/lianjia-sample.html，7 条含广告块）
class LianjiaParserTest {

    private String sample() throws IOException {
        return Files.readString(Paths.get("src/test/resources/lianjia-sample.html"), StandardCharsets.UTF_8);
    }

    @Test
    void parse_extractsHousesFromSample() throws IOException {
        List<LianjiaParser.House> houses = LianjiaParser.parse(sample());

        assertTrue(houses.size() >= 3, "样本应解析出至少 3 条房源，实际 " + houses.size());
        for (LianjiaParser.House h : houses) {
            assertTrue(h.price() > 0, "租金必须为正: " + h);
            assertTrue(!h.name().isBlank(), "标题不能为空");
        }
    }

    @Test
    void parse_skipsMalformedBlocks() {
        String html = "<div class=\"content__list--item\"><p class=\"content__list--item--title\"><a>好房源</a></p>"
                + "<p class=\"content__list--item--des\">50.00㎡</p></div>"   // 缺租金 → 跳过
                + "<div class=\"content__list--item\"><p class=\"content__list--item--title\"><a>完整房源</a></p>"
                + "<p class=\"content__list--item--des\">60.00㎡</p>"
                + "<span class=\"content__list--item-price\"><em>4200</em> 元/月</span></div>";

        List<LianjiaParser.House> houses = LianjiaParser.parse(html);

        assertEquals(1, houses.size(), "缺字段的条目应跳过");
        assertEquals("完整房源", houses.get(0).name());
        assertEquals(60.0, houses.get(0).area());
        assertEquals(4200, houses.get(0).price());
    }

    @Test
    void parse_priceRange_takesLowerBound() throws IOException {
        String html = "<div class=\"content__list--item\"><p class=\"content__list--item--title\"><a>区间价房源</a></p>"
                + "<p class=\"content__list--item--des\">30.00㎡</p>"
                + "<span class=\"content__list--item-price\"><em>3333-3444</em> 元/月</span></div>";

        List<LianjiaParser.House> houses = LianjiaParser.parse(html);

        assertEquals(1, houses.size());
        assertEquals(3333, houses.get(0).price(), "区间租金取下限");
    }

    @Test
    void parse_emptyHtml_returnsEmpty() {
        assertTrue(LianjiaParser.parse("").isEmpty());
        assertTrue(LianjiaParser.parse(null).isEmpty());
    }
}