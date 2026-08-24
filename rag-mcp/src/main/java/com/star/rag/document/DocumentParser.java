package com.star.rag.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.nio.charset.StandardCharsets;

// 文档解析：按扩展名把文件内容解析成纯文本（PDF→PDFBox；TXT/MD→UTF-8 直读）
// 解析层只管"文件 → 文字"，清洗/切片由后续工序负责
public class DocumentParser {

    // 入口：文件名 + 文件字节 → 纯文本
    // 参数用 byte[] 而非文件路径：调用方是 MCP server（收到的是协议里的二进制内容，不是磁盘路径），
    // 用字节本类就能在"任何来源的文件内容"上工作（上传管道/测试都方便）
    public String parse(String filename, byte[] content) {
        String ext = DocumentParser.extension(filename);
        return switch (ext) {                              // switch 表达式：每个分支返回一个值
            case "pdf" -> parsePdf(content);               // 二进制 PDF → PDFBox 抠字
            case "txt", "md", "markdown" -> new String(content, StandardCharsets.UTF_8);  // 文本直读
            default -> throw new IllegalArgumentException("不支持的文件类型: ." + ext);    // 安全网
        };
    }

    // PDF：PDFBox 把 PDF 里的文字抠出来
    // PDF 是二进制格式，电脑读不懂，必须靠库解析；try-with-resources 自动关文档资源
    private String parsePdf(byte[] content) {
        try (PDDocument doc = PDDocument.load(content)) {    // 加载字节 → 打开 PDF 文档对象
            PDFTextStripper stripper = new PDFTextStripper(); // 文本抽取器（PDFBox 的"抠字工具"）
            return stripper.getText(doc);                     // 按阅读顺序抽全部文字
        } catch (Exception e) {                               // 损坏/加密/扫描件无文字层都会抛
            throw new RuntimeException("PDF 解析失败: " + e.getMessage(), e);
        }
    }

    // 取小写扩展名（lastIndexOf 拿最后一个点，兼容"我的.简历.pdf"）
    // 边界：没点（dot=-1）或点在末尾（dot=长度-1）→ 无法识别，拒绝
    // public static：UploadService 的类型白名单校验也用它（单一来源，避免两处逻辑漂移）
    public static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            throw new IllegalArgumentException("无法识别文件类型: " + filename);
        }
        return filename.substring(dot + 1).toLowerCase();
    }
}
