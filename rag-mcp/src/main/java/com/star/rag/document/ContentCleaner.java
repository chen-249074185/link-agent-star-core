package com.star.rag.document;

import java.util.ArrayList;
import java.util.List;

// 内容清洗：PDF/TXT 原始文本 → 干净段落文本（去控制符/剔空行/超长截断）
// 脏文本 → 向量化质量差 → 检索不准，清洗是 RAG 质量的第一道闸
public class ContentCleaner {

    private static final int MAX_CHARS = 200_000;   // 安全上限：防病态超大文本（正常文档远达不到）

    public String clean(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        // ① 去控制字符（保留换行/制表/回车，删掉其它看不见的符号）
        String stripped = stripControl(raw);

        // ② 逐行 trim + 剔空行（PDF 解析常带一堆多余空行/行首空格）
        List<String> lines = new ArrayList<>();
        for (String line : stripped.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                lines.add(t);
            }
        }
        String joined = String.join("\n", lines);

        // ③ 超长截断（安全网；正常切片由 09-26 的切片工序负责，这里只是防病态输入）
        return joined.length() > MAX_CHARS ? joined.substring(0, MAX_CHARS) : joined;
    }

    // 去控制字符：ASCII 32 以下除换行/制表/回车全删，127(DEL) 也删
    private String stripControl(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\t' || c == '\r' || c >= ' ') {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
