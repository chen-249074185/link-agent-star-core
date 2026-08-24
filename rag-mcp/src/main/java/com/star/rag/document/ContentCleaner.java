package com.star.rag.document;

import java.util.ArrayList;
import java.util.List;

// 内容清洗：PDF/TXT 原始文本 → 干净段落文本（去控制符/空行归一化/超长截断）
// 脏文本 → 向量化质量差 → 检索不准，清洗是 RAG 质量的第一道闸
// 注意：空行是段落分隔符，必须保留（归一化为单个空行），否则下游切片器看不到段落边界
public class ContentCleaner {

    private static final int MAX_CHARS = 200_000;   // 安全上限：防病态超大文本（正常文档远达不到）

    public String clean(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        // ① 去控制字符（保留换行/制表/回车，删掉其它看不见的符号）
        String stripped = stripControl(raw);

        // ② 逐行 trim + 空行归一化：连续空行压成一个空行（段落分隔），段落首尾不留多余空行
        List<String> lines = new ArrayList<>();
        boolean prevBlank = false;
        for (String line : stripped.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) {
                if (!lines.isEmpty() && !prevBlank) {
                    lines.add("");                  // 两段之间补一个空行当分隔符
                }
                prevBlank = true;
            } else {
                lines.add(t);
                prevBlank = false;
            }
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);         // 去末尾多余空行
        }
        String joined = String.join("\n", lines);

        // ③ 超长截断（安全网；正常切片由切片工序负责，这里只是防病态输入）
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
