package com.star.agent.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 房源采集入库服务单测：mock 两个 McpConnection，验证 爬→解析→入库 链路与降级
class HouseCollectorServiceTest {

    private McpConnection crawl;
    private McpConnection rag;
    private HouseCollectorService service;

    @BeforeEach
    void setUp() {
        crawl = mock(McpConnection.class);
        rag = mock(McpConnection.class);
        service = new HouseCollectorService(Optional.of(crawl), Optional.of(rag));
    }

    @Test
    void collect_fetchesParsesUploads() {
        String sample = "<div class=\"content__list--item\"><p class=\"content__list--item--title\"><a>回龙观两居</a></p>"
                + "<p class=\"content__list--item--des\">55.00㎡</p>"
                + "<span class=\"content__list--item-price\"><em>4200</em> 元/月</span></div>";
        when(crawl.callTool(eq("crawl_fetch"), anyMap())).thenReturn(sample);
        when(rag.callTool(eq("rag_upload"), anyMap())).thenReturn("已入库 1 块");

        HouseCollectorService.CollectResult r = service.collect("u1", "北京", "https://bj.lianjia.com/zufang/");

        assertEquals(1, r.count());
        assertTrue(r.message().contains("已入库"));
        verify(crawl).callTool(eq("crawl_fetch"), anyMap());
        verify(rag).callTool(eq("rag_upload"), anyMap());
    }

    @Test
    void collect_noHousesParsed_returnsHelpWithoutUpload() {
        when(crawl.callTool(eq("crawl_fetch"), anyMap())).thenReturn("<html>无房源</html>");

        HouseCollectorService.CollectResult r = service.collect("u1", "北京", "https://bj.lianjia.com/zufang/");

        assertEquals(0, r.count());
        assertTrue(r.message().contains("解析到房源"));
        verify(rag, never()).callTool(anyString(), anyMap());
    }

    // mock 模式（无 MCP bean）：降级提示，不崩
    @Test
    void collect_noMcpConnection_degrades() {
        HouseCollectorService degraded = new HouseCollectorService(Optional.empty(), Optional.empty());

        HouseCollectorService.CollectResult r = degraded.collect("u1", "北京", "https://bj.lianjia.com/zufang/");

        assertEquals(0, r.count());
        assertTrue(r.message().contains("mock 模式"));
    }

    // 组装文本：每套一段，格式对齐 HouseTextParser（月租/面积/名称可解析）
    @Test
    void buildText_oneLinePerHouse() {
        List<LianjiaParser.House> houses = List.of(
                new LianjiaParser.House("回龙观两居", 55, 4200),
                new LianjiaParser.House("望京单间", 28, 3800));

        String text = HouseCollectorService.buildText("北京", houses);

        assertEquals("北京 回龙观两居，面积55平，月租4200元\n北京 望京单间，面积28平，月租3800元", text);
    }

    // 入库参数核对：user_id/kb_id 指向调用者默认库，filename 带城市
    @Test
    void collect_uploadsToCallersDefaultKb() {
        String sample = "<div class=\"content__list--item\"><p class=\"content__list--item--title\"><a>x</a></p>"
                + "<p class=\"content__list--item--des\">30.00㎡</p>"
                + "<span class=\"content__list--item-price\"><em>2000</em> 元/月</span></div>";
        when(crawl.callTool(eq("crawl_fetch"), anyMap())).thenReturn(sample);
        when(rag.callTool(eq("rag_upload"), anyMap())).thenReturn("ok");

        service.collect("wx_u1", "杭州", "https://hz.lianjia.com/zufang/");

        verify(rag).callTool(eq("rag_upload"), argMap(u -> "wx_u1".equals(u.get("user_id"))
                && "wx_u1-default".equals(u.get("kb_id"))
                && "杭州链家房源.txt".equals(u.get("filename"))));
    }

    private static Map<String, Object> argMap(java.util.function.Predicate<Map<String, Object>> p) {
        return org.mockito.ArgumentMatchers.argThat(p::test);
    }
}