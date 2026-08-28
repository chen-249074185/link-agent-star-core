package com.star.agent.skill.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

// AnyDocService 子进程调用测试：假 node 脚本模拟成功/失败/退出码，不打真实 anydoc
// 套路同 ffmpeg 假脚本（10-15）：nodePath/cliPath 可注入，测试指向临时 js
class AnyDocServiceTest {

    @TempDir
    Path tmp;

    // 假 cli.js：把传入的文件内容回显（模拟 anydoc 输出 markdown）
    private String fakeCliEcho() throws IOException {
        Path js = tmp.resolve("fake-anydoc.js");
        Files.writeString(js, """
                const fs = require('fs');
                process.stdout.write(fs.readFileSync(process.argv[2], 'utf8'));
                """);
        return js.toString();
    }

    private String fakeCliFail() throws IOException {
        Path js = tmp.resolve("fake-fail.js");
        Files.writeString(js, "console.error('boom'); process.exit(1);");
        return js.toString();
    }

    @Test
    void txt直读不走子进程() {
        AnyDocService svc = new AnyDocService("node", "不存在的脚本.js", 10);
        String out = svc.parse("纯文本内容 hello".getBytes(StandardCharsets.UTF_8), "笔记.txt");
        assertEquals("纯文本内容 hello", out);
    }

    @Test
    void md直读不走子进程() {
        AnyDocService svc = new AnyDocService("node", "不存在的脚本.js", 10);
        String out = svc.parse("# 标题".getBytes(StandardCharsets.UTF_8), "doc.md");
        assertEquals("# 标题", out);
    }

    @Test
    void csv走子进程解析成功() throws IOException {
        AnyDocService svc = new AnyDocService("node", fakeCliEcho(), 30);
        String out = svc.parse("a,b".getBytes(StandardCharsets.UTF_8), "data.csv");
        assertNotNull(out);
        assertEquals("a,b", out);
    }

    @Test
    void 不支持类型返回null() {
        AnyDocService svc = new AnyDocService("node", "x.js", 10);
        assertNull(svc.parse("x".getBytes(StandardCharsets.UTF_8), "virus.exe"));
    }

    @Test
    void 无扩展名返回null() {
        AnyDocService svc = new AnyDocService("node", "x.js", 10);
        assertNull(svc.parse("x".getBytes(StandardCharsets.UTF_8), "无扩展名"));
    }

    @Test
    void 子进程失败返回null() throws IOException {
        AnyDocService svc = new AnyDocService("node", fakeCliFail(), 30);
        assertNull(svc.parse("x".getBytes(StandardCharsets.UTF_8), "a.pdf"));
    }

    @Test
    void 临时文件清理() throws IOException {
        AnyDocService svc = new AnyDocService("node", fakeCliEcho(), 30);
        svc.parse("a,b".getBytes(StandardCharsets.UTF_8), "data.csv");
        java.util.List<String> names;
        try (var files = Files.list(tmp)) {
            names = files.map(p -> p.getFileName().toString()).toList();
        }
        long jsCount = names.stream().filter(n -> n.endsWith(".js")).count();
        long tmpCount = names.stream().filter(n -> n.contains("anydoc-")).count();
        assertEquals(1, jsCount);
        assertEquals(0, tmpCount, "anydoc- 临时文件应已清理");
    }
}