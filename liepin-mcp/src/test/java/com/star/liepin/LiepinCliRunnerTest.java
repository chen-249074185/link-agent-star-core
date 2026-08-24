package com.star.liepin;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// LiepinCliRunner 测试：真实 CLI 存在时跑真命令（本机已装 venv），不存在则跳过（机器无关）
class LiepinCliRunnerTest {

    // CLI 是否可用 + 路径解析：本机应解析到 mcp/liepin-cli/.venv 下的可执行文件
    @Test
    void resolvesCliPathToInstalledVenV() {
        String path = LiepinCliRunner.resolveCliPath();
        assertNotNull(path);
        assertTrue(LiepinCliRunner.cliAvailable(path), "本机应已装 liepin-cli（mcp/liepin-cli）: " + path);
    }

    // 跑 --help（无需 Token）→ 输出应包含 liepin-cli
    @Test
    void runsHelpWithoutToken() {
        String path = LiepinCliRunner.resolveCliPath();
        Assumptions.assumeTrue(LiepinCliRunner.cliAvailable(path), "本机无 liepin-cli，跳过");
        LiepinCliRunner runner = new LiepinCliRunner(path, "dummy-token");

        String out = runner.run("--help");

        assertTrue(out.contains("liepin-cli"), "帮助应含 liepin-cli，实际: " + out.substring(0, Math.min(80, out.length())));
    }

    // CLI 不可用 → cliAvailable 返回 false（不抛）
    @Test
    void cliUnavailable_pathFalse() {
        assertFalse(LiepinCliRunner.cliAvailable("Z:/no/such/liepin-cli.exe"));
    }
}