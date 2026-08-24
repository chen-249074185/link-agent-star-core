package com.star.rag.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// DocumentParser 单测：TXT/MD 直读 + PDF 解析（PDFBox 现造一个）+ 不支持类型拒绝
class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    @Test
    void txt_readsDirectly() {
        String text = parser.parse("说明.txt", "北京租房指南".getBytes(StandardCharsets.UTF_8));
        assertEquals("北京租房指南", text);
    }

    @Test
    void md_readsDirectly() {
        String text = parser.parse("readme.md", "# 标题\n\n正文内容".getBytes(StandardCharsets.UTF_8));
        assertEquals("# 标题\n\n正文内容", text);
    }

    // PDF：用 PDFBox 造一个最小 PDF（ASCII 内容避免非内嵌字体中文乱码）→ 再解析回来
    @Test
    void pdf_extractsText() throws Exception {
        String original = "Hello PDFBox RAG Parsing Test";
        byte[] pdf = makePdf(original);

        String text = parser.parse("报告.pdf", pdf);
        assertTrue(text.contains("PDFBox"), "PDF 解析应包含正文，实际: " + text);
    }

    @Test
    void unsupportedExtension_rejects() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("evil.exe", new byte[]{1, 2, 3}));
    }

    @Test
    void noExtension_rejects() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("README", "abc".getBytes(StandardCharsets.UTF_8)));
    }

    // 用 PDFBox 现造一个最小 PDF：一页 + 一行文字
    private byte[] makePdf(String text) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            doc.save(out);
        }
        return out.toByteArray();
    }
}
