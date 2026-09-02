package com.star.agent.skill.impl;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// POI 解析器单测：用 POI 现造 docx/xlsx/pptx（写端读端同库，验证结构提取不依赖真实 Office）
class PoiDocParserTest {

    private final PoiDocParser parser = new PoiDocParser();

    @Test
    void supports_knownExts() {
        assertTrue(PoiDocParser.supports("docx"));
        assertTrue(PoiDocParser.supports("xlsx"));
        assertTrue(PoiDocParser.supports("pptx"));
        assertTrue(PoiDocParser.supports("pdf"));
        assertTrue(PoiDocParser.supports("csv"));
        assertFalse(PoiDocParser.supports("doc"));    // 老二进制格式明确不支持（anydoc 已移除）
        assertFalse(PoiDocParser.supports("odt"));
        assertFalse(PoiDocParser.supports(null));
    }

    @Test
    void extOf_stripsDotAndLowercases() {
        assertEquals("docx", PoiDocParser.extOf("报告.DOCX"));
        assertEquals("pdf", PoiDocParser.extOf("a.b.pdf"));
        assertEquals("", PoiDocParser.extOf("无扩展名"));
        assertEquals("", PoiDocParser.extOf(null));
        assertEquals("", PoiDocParser.extOf("trailing."));
    }

    // Word：段落 + 表格内容都要提取（标题/正文/表都是 RAG 素材）
    @Test
    void docx_extractsParagraphsAndTables() throws Exception {
        byte[] docx;
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFParagraph p1 = doc.createParagraph();
            p1.createRun().setText("北京租房指南");
            XWPFParagraph p2 = doc.createParagraph();
            p2.createRun().setText("海淀区房源充足，均价 6500 元/月。");
            XWPFTable table = doc.createTable(1, 2);
            table.getRow(0).getCell(0).setText("小区");
            table.getRow(0).getCell(1).setText("月租");
            doc.write(out);
            docx = out.toByteArray();
        }

        String text = parser.parse(docx, "docx");

        assertTrue(text.contains("北京租房指南"));
        assertTrue(text.contains("海淀区房源充足"));
        assertTrue(text.contains("小区 | 月租"));
    }

    // Excel：工作表名 + 单元格内容制表符分隔
    @Test
    void xlsx_extractsSheetsAndCells() throws Exception {
        byte[] xlsx;
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb =
                     new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("房源");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("小区");
            row.createCell(1).setCellValue("月租");
            Row row2 = sheet.createRow(1);
            row2.createCell(0).setCellValue("回龙观");
            row2.createCell(1).setCellValue("4500");
            wb.write(out);
            xlsx = out.toByteArray();
        }

        String text = parser.parse(xlsx, "xlsx");

        assertTrue(text.contains("【房源】"));
        assertTrue(text.contains("小区\t月租"));
        assertTrue(text.contains("回龙观\t4500"));
    }

    // PPT：每页文本框内容提取
    @Test
    void pptx_extractsSlideText() throws Exception {
        byte[] pptx;
        try (XMLSlideShow ppt = new XMLSlideShow();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSLFSlide slide = ppt.createSlide();
            XSLFTextBox box = slide.createTextBox();
            box.setText("杭州租房片区分析");
            ppt.write(out);
            pptx = out.toByteArray();
        }

        String text = parser.parse(pptx, "pptx");

        assertTrue(text.contains("杭州租房片区分析"));
    }

    // 不支持的类型 → null（明确不支持，话术由调用方给）
    @Test
    void unsupportedExt_returnsNull() {
        assertNull(parser.parse("内容".getBytes(), "doc"));
        assertNull(parser.parse("内容".getBytes(), "odt"));
    }

    // PDF：PDFBox 现造 PDF → 抠字回文（与 rag-mcp DocumentParser 同款验证思路）
    @Test
    void pdf_extractsText() throws Exception {
        byte[] pdf;
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument();
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
            doc.addPage(page);
            try (org.apache.pdfbox.pdmodel.PDPageContentStream cs =
                         new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(org.apache.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("Beijing rental guide");
                cs.endText();
            }
            doc.save(out);
            pdf = out.toByteArray();
        }

        String text = parser.parse(pdf, "pdf");

        assertTrue(text.contains("Beijing rental guide"));
    }

    // CSV：直读拆行（无解析器）
    @Test
    void csv_readsLines() {
        String text = parser.parse("小区,月租\n回龙观,4500\n".getBytes(), "csv");
        assertTrue(text.contains("小区,月租"));
        assertTrue(text.contains("回龙观,4500"));
    }

    // 损坏文件 → null 不抛异常（降级信号）
    @Test
    void corruptedBytes_returnsNull() {
        assertNull(parser.parse("不是合法的 docx 二进制".getBytes(), "docx"));
    }

    // 空内容 → null
    @Test
    void emptyContent_returnsNull() {
        assertNull(parser.parse(new byte[0], "docx"));
    }

    // 解析结果可被下游当文本用（非空）
    @Test
    void docx_textNotEmpty() throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("测试");
            doc.write(out);
            String text = parser.parse(out.toByteArray(), "docx");
            assertEquals("测试\n", text);
        }
    }
}