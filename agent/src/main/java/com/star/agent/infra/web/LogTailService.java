package com.star.agent.infra.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

// 日志审计数据源（管理端"日志审计"Tab，W12 10-30）：
// 读 logback 文件 appender 落盘的 logs/agent.log，按关键词过滤返回最近 N 行
// 日志里带 [%X{traceId}]，管理端可按 traceId 查询全链路日志
@Component
public class LogTailService {

    private static final Logger logger = LoggerFactory.getLogger(LogTailService.class);

    private static final String DEFAULT_LOG_PATH = "logs/agent.log";
    // 全文件行数上限：超过只读尾部（演示规模日志不大，直接全读也行，加个保护）
    private static final int MAX_LINES_READ = 5000;

    private final String logPath;

    public LogTailService() {
        this(DEFAULT_LOG_PATH);
    }

    // 测试可注入假路径（指向临时日志文件）
    public LogTailService(String logPath) {
        this.logPath = logPath;
    }

    // 返回匹配关键词的最近 max 行（无关键词=全部最近行）；文件不存在/读失败返回单条提示不崩
    public List<String> tail(String keyword, int max) {
        Path file = Paths.get(logPath);
        if (!Files.exists(file)) {
            return List.of("日志文件不存在（应用启动并打过日志后生成 " + logPath + "）");
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int total = lines.size();
            if (total > MAX_LINES_READ) {
                lines = lines.subList(total - MAX_LINES_READ, total);   // 只读尾部，防大文件全量加载
            }
            List<String> matched = new ArrayList<>();
            for (String line : lines) {
                if (keyword == null || keyword.isBlank() || line.contains(keyword)) {
                    matched.add(line);
                }
            }
            if (matched.isEmpty()) {
                return List.of("未找到匹配的日志行");
            }
            int from = Math.max(0, matched.size() - Math.max(1, max));
            return matched.subList(from, matched.size());
        } catch (Exception e) {
            logger.warn("读取日志文件失败: {}", e.getMessage());
            return List.of("读取日志失败: " + e.getMessage());
        }
    }
}