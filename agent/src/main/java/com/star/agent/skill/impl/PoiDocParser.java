package com.star.agent.skill.impl;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

// 文档文本提取器（11-02 起全 Java，不再依赖 anydoc Node 子进程——实测其解析 docx 卡死超时）：
//   docx/xlsx/pptx = Apache POI；pdf = PDFBox（rag-mcp 同款）；csv = 直读拆行
//   txt/md 由调用方直读（UTF-8）；doc/odt/rtf/epub 不支持（明确话术）
// 失败返回 null 降级信号，不抛异常
@Component
public class PoiDocParser {

    private static final Logger logger = LoggerFactory.getLogger(PoiDocParser.class);

    // 支持解析的扩展名（与 AnyDocService 时代一致的小写无点格式）
    private static final List<String> SUPPORTED_EXTS = List.of("docx", "xlsx", "pptx", "pdf", "csv");

    public static boolean supports(String ext) {
        return ext != null && SUPPORTED_EXTS.contains(ext);
    }

    // 扩展名（小写，无点）；无扩展名返回空串（上传/识别通道共用，单一来源）
    public static String extOf(String filename) {
        if (filename == null) return "";
        int i = filename.lastIndexOf('.');
        return i < 0 || i == filename.length() - 1 ? "" : filename.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    // 解析字节 → 纯文本（段落/表格/单元格都提取）；不支持或解析失败返回 null
    public String parse(byte[] content, String ext) {
        if (content == null || content.length == 0) {
            return null;
        }
        try {
            switch (ext) {
                case "docx":
                    return parseDocx(content);
                case "xlsx":
                    return parseXlsx(content);
                case "pptx":
                    return parsePptx(content);
                case "pdf":
                    return parsePdf(content);
                case "csv":
                    return parseCsv(content);
                default:
                    return null;
            }
        } catch (Exception e) {
            logger.warn("文档解析 {} 失败: {}", ext, e.getMessage());
            return null;
        }
    }

    // Word：段落 + 表格逐格（标题/正文/表都有文字，RAG 要全量）
    private String parseDocx(byte[] content) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(content))) {
            for (XWPFParagraph p : doc.getParagraphs()) {
                String t = p.getText().trim();
                if (!t.isEmpty()) {
                    sb.append(t).append('\n');
                }
            }
            for (XWPFTable table : doc.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    StringBuilder line = new StringBuilder();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        String t = cell.getText().trim();
                        if (!t.isEmpty()) {
                            if (line.length() > 0) line.append(" | ");
                            line.append(t);
                        }
                    }
                    if (line.length() > 0) {
                        sb.append(line).append('\n');
                    }
                }
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    // Excel：每个工作表一行【表名】，行 → 单元格制表符分隔
    private String parseXlsx(byte[] content) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb =
                     new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(content))) {
            for (Sheet sheet : wb) {
                sb.append('【').append(sheet.getSheetName()).append("】\n");
                for (Row row : sheet) {
                    StringBuilder line = new StringBuilder();
                    for (Cell cell : row) {
                        String t = cell.toString().trim();
                        if (!t.isEmpty()) {
                            if (line.length() > 0) line.append('\t');
                            line.append(t);
                        }
                    }
                    if (line.length() > 0) {
                        sb.append(line).append('\n');
                    }
                }
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    // PPT：每页文本形状 → 换行分隔
    private String parsePptx(byte[] content) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(content))) {
            for (XSLFSlide slide : ppt.getSlides()) {
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String t = textShape.getText().trim();
                        if (!t.isEmpty()) {
                            sb.append(t).append('\n');
                        }
                    }
                }
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    // PDF：PDFBox 抠字（rag-mcp 同款 2.0.31）
    private String parsePdf(byte[] content) throws IOException {
        try (PDDocument doc = PDDocument.load(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc).trim();
            return text.isEmpty() ? null : text;
        }
    }

    // CSV：直读拆行（逗号分隔的文本，每行一条记录），不需要解析器
    private String parseCsv(byte[] content) {
        String raw = new String(content, StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\r?\n")) {
            if (!line.isBlank()) {
                sb.append(line.trim()).append('\n');
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}