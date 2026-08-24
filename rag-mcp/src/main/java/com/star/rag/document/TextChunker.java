package com.star.rag.document;

import java.util.ArrayList;
import java.util.List;

// 智能切片：清洗后的整篇文本 → 若干语义完整的切片（chunk）
// 策略三件套：段落切（按空行分自然段）+ 512 定长兜底（超长段按长度切）+ overlap 50（相邻块重叠防切断语义）
// 注：字符数近似 token 数（中文 1 字≈1 token 的简化估算，真实 tokenizer 可后续替换）
public class TextChunker {

    private static final int MAX_CHARS = 512;       // 单块上限
    private static final int OVERLAP_CHARS = 50;    // 相邻块重叠量

    public List<String> chunk(String text) {
        List<String> result = new ArrayList<>();
        // 按空行分段：\n\n 或 \n+空白+\n 都算段落边界（单 \n 不算，一段内换行保持完整）
        for (String para : text.split("\\n\\s*\\n")) {
            String p = para.trim();
            if (p.isEmpty()) {
                continue;                           // 空段落跳过
            }
            if (p.length() <= MAX_CHARS) {
                result.add(p);                      // 短段落 = 一个完整块
            } else {
                result.addAll(splitLong(p));        // 超长段 → 定长 + 重叠
            }
        }
        return result;
    }

    // 超长段：按 MAX_CHARS 切块，每块前进 step = MAX - OVERLAP → 相邻块天然重叠 OVERLAP
    private List<String> splitLong(String para) {
        int step = MAX_CHARS - OVERLAP_CHARS;
        if (step <= 0) {
            step = 1;                               // 配置兜底：防死循环
        }
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < para.length()) {
            int end = Math.min(start + MAX_CHARS, para.length());
            pieces.add(para.substring(start, end));
            if (end == para.length()) {
                break;                              // 最后一块到末尾就停
            }
            start += step;
        }
        return pieces;
    }
}
