package com.star.liepin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// 官方 liepin-cli 子进程调用器（Java 壳的核心）：拼命令 → 注入 Token → 跑子进程 → 收 JSON 输出
// 壳只负责"传话"，真实请求由官方 CLI 完成（合规：Token 是猎聘官方授权，不是逆向接口）
public class LiepinCliRunner {

    private static final long TIMEOUT_SECONDS = 60;   // CLI 可能慢（60 次/分钟限流排队），给足超时

    private final String cliPath;                     // liepin-cli 可执行文件路径（Windows .exe）
    private final String token;                       // 猎聘授权 Token（x-user-token）

    public LiepinCliRunner(String cliPath, String token) {
        this.cliPath = cliPath;
        this.token = token;
    }

    // 执行一条命令（业务参数已拼好），返回 stdout 全文；失败抛 RuntimeException
    public String run(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(cliPath);
        cmd.addAll(List.of(args));
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            // Token 只进子进程环境变量（--token 会出现在进程命令行里，环境变量更隐蔽）
            pb.environment().put("LIEPIN_USER_TOKEN", token);
            // 关键坑：Windows 下 Python 默认按控制台编码(GBK)输出，必须强制 UTF-8，否则中文全乱码
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            byte[] bytes = p.getInputStream().readAllBytes();
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new RuntimeException("liepin-cli 执行超时: " + String.join(" ", cmd));
            }
            if (p.exitValue() != 0) {
                throw new RuntimeException("liepin-cli 退出码 " + p.exitValue() + ": " + truncate(decode(bytes)));
            }
            return decode(bytes);   // UTF-8 优先，乱码则 GBK 兜底（防 Python 忽略 PYTHONIOENCODING 的场景）
        } catch (IOException e) {
            throw new RuntimeException("liepin-cli 无法启动: " + e.getMessage() + "（路径: " + cliPath + "）", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("liepin-cli 被中断", e);
        }
    }

    // CLI 是否可用（doctor 自检用）：可执行文件存在（找不到则试 PATH 里的 liepin-cli）
    public static boolean cliAvailable(String cliPath) {
        if (cliPath != null && !cliPath.isBlank()) {
            return Files.isExecutable(Paths.get(cliPath));
        }
        return false;
    }

    // 环境变量/默认值解析：LIEPIN_CLI_PATH 优先
    // 其次 jar 位置推导仓库根（liepin-mcp/target/xxx.jar → 仓库根 → 根/mcp/liepin-cli），与工作目录无关
    public static String resolveCliPath() {
        String fromEnv = System.getenv("LIEPIN_CLI_PATH");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        try {
            Path jarDir = Path.of(LiepinCliRunner.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).getParent();
            Path repoRoot = jarDir.getParent().getParent();   // .../liepin-mcp/target → 仓库根
            String win = repoRoot.resolve("mcp/liepin-cli/.venv/Scripts/liepin-cli.exe").toString();
            String unix = repoRoot.resolve("mcp/liepin-cli/.venv/bin/liepin-cli").toString();
            if (Files.exists(Path.of(win))) return win;
            if (Files.exists(Path.of(unix))) return unix;
        } catch (Exception ignored) {
        }
        // 最后兜底：PATH 里的 liepin-cli
        return "liepin-cli";
    }

    private String truncate(String s) {
        return s == null ? "" : (s.length() > 300 ? s.substring(0, 300) + "..." : s);
    }

    // 解码：UTF-8 优先；出现替换符 � 说明编码不对 → GBK 兜底（Windows Python 常见）
    private String decode(byte[] bytes) {
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (!utf8.contains("\uFFFD")) {
            return utf8;
        }
        return new String(bytes, java.nio.charset.Charset.forName("GBK"));
    }

    public String token() {
        return token;
    }

    public String cliPathVisible() {
        return cliPath;
    }
}