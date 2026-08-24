package com.star.rag.document;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// ContentCleaner 单测：去控制符/保留换行制表/剔空行/超长截断
class ContentCleanerTest {

    private final ContentCleaner cleaner = new ContentCleaner();

    @Test
    void nullOrEmpty_returnsEmpty() {
        assertEquals("", cleaner.clean(null));
        assertEquals("", cleaner.clean(""));
    }

    // 控制字符删掉，但换行/制表/回车保留
    @Test
    void stripControl_keepsLineBreaks() {
        assertEquals("ab\nc", cleaner.clean("a\u0001b\nc\u0002"));   // \u0001/\u0002 被删
        assertEquals("a\tb", cleaner.clean("a\u0000\tb"));
    }

    // 逐行 trim + 空行归一化：连续空行压成一个（段落分隔符保留），段落首尾不留空行
    @Test
    void trimsAndNormalizesBlankLines() {
        String out = cleaner.clean("  第一行  \n\n    \n\n  第二行  \n");
        assertEquals("第一行\n\n第二行", out);
    }

    // 超长截断：超过安全上限截到 MAX_CHARS
    @Test
    void overlong_truncated() {
        String big = "a".repeat(200_005);
        String out = cleaner.clean(big);
        assertEquals(200_000, out.length());
        assertTrue(out.startsWith("aaaa"));
    }
}
