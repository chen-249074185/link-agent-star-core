package com.star.agent.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
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

// 房源采集入库服务单测：mock 两个 McpConnection，验证 爬→解析→写文件→入库 链路与降级
class HouseCollectorServiceTest {

    private McpConnection crawl;
    private McpConnection rag;
    private HouseCollectorService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        crawl = mock(McpConnection.class);
        rag = mock(McpConnection.class);
        service = new HouseCollectorService(Optional.of(crawl), Optional.of(rag), tempDir);
    }

    private String sampleHtml(String title, String area, String price) {
        return "<div class=\"content__list--item\"><p class=\"content__list--item--title\"><a>" + title + "</a></p>"
                + "<p class=\"content__list--item--des\">" + area + "㎡</p>"
                + "<span class=\"content__list--item-price\"><em>" + price + "</em> 元/月</span></div>";
    }

    @Test
    void collect_writesFileThenUploads() throws Exception {
        when(crawl.callTool(eq("crawl_fetch"), anyMap())).thenReturn(sampleHtml("回龙观两居 首次出租", "55.00", "4200"));
        when(rag.callTool(eq("rag_upload"), anyMap())).thenReturn("已入库 1 块");
        when(rag.callTool(eq("rag_admin_delete_kb"), anyMap())).thenReturn("已删除知识库");

        HouseCollectorService.CollectResult r = service.collect("u1", "北京", "https://bj.lianjia.com/zufang/");

        assertEquals(1, r.count());
        assertTrue(r.message().contains("已重建索引"), r.message());
        // 数据文件已写入（用户可点开查看/编辑的文本源）
        String fileContent = Files.readString(tempDir.resolve("北京.txt"), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(fileContent.contains("回龙观两居"), "数据文件应含房源");
        assertTrue(fileContent.contains("月租4200元"));
        assertTrue(!fileContent.contains("首次出租"), "广告词应被清洗");
        verify(rag).callTool(eq("rag_admin_delete_kb"), anyMap());   // 覆盖语义：先清共享库防重复
        verify(rag).callTool(eq("rag_upload"), anyMap());
    }

    @Test
    void collect_noHousesParsed_noFileNoUpload() {
        when(crawl.callTool(eq("crawl_fetch"), anyMap())).thenReturn("<html>无房源</html>");

        HouseCollectorService.CollectResult r = service.collect("u1", "北京", "https://bj.lianjia.com/zufang/");

        assertEquals(0, r.count());
        assertTrue(r.message().contains("解析到房源"));
        verify(rag, never()).callTool(anyString(), anyMap());
    }

    // mock 模式（无 MCP bean）：降级提示，不崩
    @Test
    void collect_noMcpConnection_degrades() {
        HouseCollectorService degraded = new HouseCollectorService(Optional.empty(), Optional.empty(), tempDir);

        HouseCollectorService.CollectResult r = degraded.collect("u1", "北京", "https://bj.lianjia.com/zufang/");

        assertEquals(0, r.count());
        assertTrue(r.message().contains("mock 模式"));
    }

    // 用户手动编辑数据文件后：reindexAll 一次删库 + 全部城市文件上传（不重新爬）
    @Test
    void reindexAll_readsAllFilesAndUploads() throws Exception {
        Files.writeString(tempDir.resolve("杭州.txt"),
                "杭州 文三路一居，面积40平，月租4500元\n\n杭州 长河两居，面积80平，月租5600元",
                java.nio.charset.StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("成都.txt"),
                "成都 天府三街一居，面积45平，月租3200元",
                java.nio.charset.StandardCharsets.UTF_8);
        when(rag.callTool(eq("rag_upload"), anyMap())).thenReturn("已入库 2 块");
        when(rag.callTool(eq("rag_admin_delete_kb"), anyMap())).thenReturn("已删除知识库");

        String result = service.reindexAll("u1");

        assertTrue(result.contains("2 个城市"), "全部城市文件一起重建: " + result);
        verify(rag, org.mockito.Mockito.times(1)).callTool(eq("rag_admin_delete_kb"), anyMap());   // 只清一次共享库（防多城市互相覆盖）
        verify(rag, org.mockito.Mockito.times(2)).callTool(eq("rag_upload"), anyMap());
    }

    @Test
    void reindexAll_noFiles_returnsHelp() {
        String result = service.reindexAll("u1");

        assertTrue(result.contains("没有房源文件"));
        verify(rag, never()).callTool(anyString(), anyMap());
    }

    // 组装文本：每套一段（空行分隔 → 切片器按段切每套一块）
    @Test
    void buildText_blankLineBetweenHouses() {
        List<LianjiaParser.House> houses = List.of(
                new LianjiaParser.House("回龙观两居", 55, 4200),
                new LianjiaParser.House("望京单间", 28, 3800));

        String text = HouseCollectorService.buildText("北京", houses);

        assertEquals("北京 回龙观两居，面积55平，月租4200元\n\n北京 望京单间，面积28平，月租3800元", text);
    }

    // 入库参数核对：房源固定写入共享房源库（system 身份，全用户只读可检索），filename 带城市
    @Test
    void collect_uploadsToSharedHousesKb() {
        when(crawl.callTool(eq("crawl_fetch"), anyMap())).thenReturn(sampleHtml("x", "30.00", "2000"));
        when(rag.callTool(eq("rag_upload"), anyMap())).thenReturn("ok");
        when(rag.callTool(eq("rag_admin_delete_kb"), anyMap())).thenReturn("ok");

        service.collect("wx_u1", "杭州", "https://hz.lianjia.com/zufang/");

        verify(rag).callTool(eq("rag_admin_delete_kb"), argMap(u ->
                HouseCollectorService.SHARED_HOUSES_KB.equals(u.get("kb_id"))));
        verify(rag).callTool(eq("rag_upload"), argMap(u -> HouseCollectorService.SHARED_OWNER.equals(u.get("user_id"))
                && HouseCollectorService.SHARED_HOUSES_KB.equals(u.get("kb_id"))
                && "杭州链家房源.txt".equals(u.get("filename"))));
    }

    private static Map<String, Object> argMap(java.util.function.Predicate<Map<String, Object>> p) {
        return org.mockito.ArgumentMatchers.argThat(p::test);
    }
}