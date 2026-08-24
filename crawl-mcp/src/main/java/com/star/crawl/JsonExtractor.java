package com.star.crawl;

// 内嵌 JSON 解析：从网页里挖出结构化数据（房源列表常藏在 <script type="application/ld+json"> 或 window.XXX = {...} 里）
// 策略：① 优先 ld+json 脚本块 → ② 兜底在 script 里找平衡括号 JSON（字符串字面量感知，防引号内的花括号误判）
public class JsonExtractor {

    // 入口：HTML → 找到的第一个 JSON 字符串（找不到返回 null，调用方降级）
    public String extractJson(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        // ① 优先：ld+json 脚本块（搜索引擎/平台标准做法）
        String ldJson = firstLdJson(html);
        if (ldJson != null) {
            return ldJson;
        }
        // ② 兜底：script 里挖平衡括号 JSON
        return firstScriptJson(html);
    }

    // <script type="application/ld+json">...</script> 里的内容
    private String firstLdJson(String html) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?is)<script[^>]*type\\s*=\\s*[\"']?application/ld\\+json[\"']?[^>]*>(.*?)</script>")
                .matcher(html);
        if (m.find()) {
            String json = m.group(1).trim();
            return json.isEmpty() ? null : json;
        }
        return null;
    }

    // script 标签里从第一个 { 开始做平衡括号提取（带字符串字面量感知）
    private String firstScriptJson(String html) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?is)<script[^>]*>(.*?)</script>")
                .matcher(html);
        while (m.find()) {
            String s = m.group(1);
            String json = balancedBraceJson(s);
            if (json != null) {
                return json;
            }
        }
        return null;
    }

    // 从字符串里找第一个 { ... } 平衡块：遇引号跳过字符串内容（\ 转义感知），花括号计数归零即截取
    private String balancedBraceJson(String s) {
        int start = s.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return s.substring(start, i + 1);   // 平衡闭合 → 截取
                }
            }
        }
        return null;   // 没闭合：不是完整 JSON
    }
}