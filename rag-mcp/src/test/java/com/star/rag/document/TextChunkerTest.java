package com.star.rag.document;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// TextChunker 单测：段落切/超长定长/overlap/空段落
class TextChunkerTest {

    private final TextChunker chunker = new TextChunker();

    // 段落切：空行分隔的自然段各成一块
    @Test
    void splitsByParagraph() {
        List<String> chunks = chunker.chunk("第一段讲通勤。\n\n第二段讲房租。");
        assertEquals(2, chunks.size());
        assertEquals("第一段讲通勤。", chunks.get(0));
        assertEquals("第二段讲房租。", chunks.get(1));
    }

    // 单换行不算段落边界（一段内的换行保持完整）
    @Test
    void singleNewline_keepsParagraphWhole() {
        List<String> chunks = chunker.chunk("第一行\n第二行");
        assertEquals(1, chunks.size());
        assertEquals("第一行\n第二行", chunks.get(0));
    }

    // 短段落完整保留，不被切碎
    @Test
    void shortParagraph_keptWhole() {
        List<String> chunks = chunker.chunk("短段。\n\n也很短。");
        assertEquals(2, chunks.size());
    }

    // 超长段：切成 ≤512 的若干块，相邻块重叠 50 字符（防边界切断语义）
    @Test
    void longParagraph_splitIntoOverlappingPieces() {
        String longPara = "a".repeat(1000);
        List<String> chunks = chunker.chunk(longPara);

        assertTrue(chunks.size() >= 2);
        for (String c : chunks) {
            assertTrue(c.length() <= 512, "单块超限: " + c.length());
        }
        // overlap 验证：块0 末尾 50 字符 == 块1 开头 50 字符
        String c0 = chunks.get(0);
        String c1 = chunks.get(1);
        assertEquals(c0.substring(c0.length() - 50), c1.substring(0, 50));
    }

    // 空段落/纯空白：跳过，不出脏块
    @Test
    void blankParagraphs_skipped() {
        List<String> chunks = chunker.chunk("\n\n   \n\n有效内容\n\n");
        assertEquals(1, chunks.size());
        assertEquals("有效内容", chunks.get(0));
    }

    // 混合：短段 + 超长段混排，数量正确且块序稳定
    @Test
    void mixedShortAndLong() {
        String text = "短段。\n\n" + "b".repeat(600) + "\n\n尾部短段。";
        List<String> chunks = chunker.chunk(text);
        assertEquals(4, chunks.size());   // 短1 + 超长600切成2(512+138) + 短1
        assertEquals("短段。", chunks.get(0));
        assertEquals("尾部短段。", chunks.get(3));
    }
}
