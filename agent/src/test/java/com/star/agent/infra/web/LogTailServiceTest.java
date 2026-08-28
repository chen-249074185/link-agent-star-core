package com.star.agent.infra.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// LogTailService 单测：真实临时文件按关键词过滤 + 尾行截断 + 文件缺失降级
class LogTailServiceTest {

    @TempDir
    Path tempDir;

    private Path writeLog(String content) throws Exception {
        Path f = tempDir.resolve("agent.log");
        Files.write(f, content.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    @Test
    void tail_filtersByKeyword() throws Exception {
        Path f = writeLog("10:00:00 [a1] INFO 开始\n10:00:01 [a1] ERROR 出错了\n10:00:02 [b2] INFO 正常\n");
        LogTailService svc = new LogTailService(f.toString());

        List<String> lines = svc.tail("ERROR", 100);

        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("ERROR"));
    }

    @Test
    void tail_withoutKeyword_returnsAllWithinLimit() throws Exception {
        Path f = writeLog("line1\nline2\nline3\n");
        LogTailService svc = new LogTailService(f.toString());

        assertEquals(3, svc.tail(null, 100).size());
        assertEquals(2, svc.tail("", 2).size());   // 最近 2 行（line2/line3）
    }

    @Test
    void tail_noMatch_returnsHint() throws Exception {
        Path f = writeLog("line1\nline2\n");
        LogTailService svc = new LogTailService(f.toString());

        assertEquals(List.of("未找到匹配的日志行"), svc.tail("ghost", 100));
    }

    @Test
    void tail_missingFile_returnsHintNotCrash() throws Exception {
        LogTailService svc = new LogTailService(tempDir.resolve("nope.log").toString());

        List<String> lines = svc.tail("x", 100);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("不存在"));
    }
}