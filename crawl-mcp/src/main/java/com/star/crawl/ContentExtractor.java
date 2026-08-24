package com.star.crawl;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 正文密度提取：网页的导航/菜单/广告全是链接（纯文字占比低=密度低），正文段落是连续文字（密度高）
// 策略：剥脚本样式 → 按块级标签切块 → 每块算"纯文字/(纯文字+链接文字)"密度 → 密度达标里挑最长的
public class ContentExtractor {

    private static final double MIN_DENSITY = 0.3;    // 密度及格线：链接太多 = 导航/菜单，不要
    private static final int MIN_TEXT_LENGTH = 30;    // 正文块最小长度：太短是标题/按钮之类

    // 入口：HTML → 正文纯文本（无正文返回空串，调用方降级）
    public String extractMainText(String html) {
        // ① 剥掉脚本/样式/注释（里面的文字会污染密度计算）
        String cleaned = html
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<!--.*?-->", " ");

        // ② 按块级标签切块（p/div/article/section/标题/列表项等，标签内容=候选块）
        String[] blocks = cleaned.split("(?is)</?(p|div|article|section|h[1-6]|li|td|tr|br)[^>]*>");

        String best = "";
        for (String block : blocks) {
            String text = textOf(block);     // 块内纯文字
            String links = linksOf(block);   // 块内链接里的文字
            int t = text.length();
            int l = links.length();
            if (t < MIN_TEXT_LENGTH) {
                continue;                    // 太短：标题/按钮/碎料，跳过
            }
            double density = (double) t / (t + l);   // 密度 = 纯文字 / 全部文字
            if (density > MIN_DENSITY && t > best.length()) {
                best = text;                 // 密度达标且更长 → 记为当前最佳（最长达标块=正文）
            }
        }
        return best;
    }

    // 块内纯文字：去标签、解常见实体、多空白压一个
    private String textOf(String block) {
        return block.replaceAll("<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("\\s+", " ")
                .trim();
    }

    // 块内链接文字（<a>...</a> 里的文字拼一起，算密度用）
    private String linksOf(String block) {
        Matcher m = Pattern.compile("(?is)<a[^>]*>(.*?)</a>").matcher(block);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            sb.append(m.group(1).replaceAll("<[^>]+>", " "));
        }
        return sb.toString();
    }
}