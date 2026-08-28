package com.star.agent.skill.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

// anydoc 文档识别服务（firecrawl/anydoc，19k★）：Word/PPT/Excel/ODT/RTF/EPUB/CSV/PDF → Markdown
// 子进程模式（同 liepin-cli）：vendored 在 agent/anydoc/（gitignore），node cli.js <file> 输出 Markdown 到 stdout
// txt/md 无需解析器，Java 侧直读返回；其余格式走 anydoc；失败返回 null 降级信号，不抛异常
@Component
public class AnyDocService {

    private static final Logger logger = LoggerFactory.getLogger(AnyDocService.class);

    // anydoc 支持的格式（txt/md 直接直读，不进子进程）
    private static final List<String> ANYDOC_EXTS = List.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "csv", "odt", "ods", "odp", "rtf", "epub");

    private final String nodePath;
    private final String cliPath;
    private final long timeoutSeconds;

    // Spring 版：路径来自配置（application.properties），单构造器避免坑15 重载冲突
    @Autowired
    public AnyDocService(@Value("${linkagent.anydoc.node:node}") String nodePath,
                         @Value("${linkagent.anydoc.path:anydoc/node_modules/@firecrawl/anydoc/cli.js}") String cliPath) {
        this(nodePath, cliPath, 60);
    }

    // 测试用：注入假 cli 脚本 + 短超时
    public AnyDocService(String nodePath, String cliPath, long timeoutSeconds) {
        this.nodePath = nodePath;
        this.cliPath = cliPath;
        this.timeoutSeconds = timeoutSeconds;
    }

    // 解析文档 → Markdown 文本；失败返回 null（降级信号）
    public String parse(byte[] content, String filename) {
        String ext = extOf(filename);
        if (ext.isEmpty()) {
            return null;
        }
        // txt/md 不劳烦子进程，直读即可
        if ("txt".equals(ext) || "md".equals(ext) || "markdown".equals(ext)) {
            return new String(content, StandardCharsets.UTF_8);
        }
        if (!ANYDOC_EXTS.contains(ext)) {
            logger.warn("anydoc 不支持的文件类型: {}", ext);
            return null;
        }

        Path tmp = null;
        try {
            tmp = Files.createTempFile("anydoc-", "." + ext);
            Files.write(tmp, content);
            ProcessBuilder pb = new ProcessBuilder(nodePath, cliPath, tmp.toString());
            pb.redirectErrorStream(true);   // 合并 stderr，防管道写满死锁
            Process p = pb.start();
            boolean done = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!done) {
                logger.warn("anydoc 解析超时({}s): {}", timeoutSeconds, filename);
                p.destroyForcibly();
                return null;
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.exitValue() != 0) {
                logger.warn("anydoc 解析失败: exit={}, msg={}", p.exitValue(), firstLine(out));
                return null;
            }
            return out;
        } catch (IOException | InterruptedException e) {
            logger.warn("anydoc 调用异常: {}", e.getMessage());
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                }
            }
        }
    }

    // 扩展名（小写，无点）；无扩展名返回空串
    static String extOf(String filename) {
        if (filename == null) return "";
        int i = filename.lastIndexOf('.');
        return i < 0 || i == filename.length() - 1 ? "" : filename.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    private static String firstLine(String s) {
        if (s == null || s.isBlank()) return "";
        int i = s.indexOf('\n');
        return (i < 0 ? s : s.substring(0, i)).trim();
    }
}