package com.star.agent.adapter.web;

import com.star.agent.infra.web.Result;
import com.star.agent.mcp.McpConnection;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

// RAG 管理接口（面向用户的 HTTP 层）：upload|list|delete|search
// 底层全部走 MCP 协议调 rag-mcp 子进程（与 McpSkill 同一通道）；mock 模式无 McpConnection → 503
// 注：userId 暂由参数传入（用户体系 JWT 后改为从会话取，接口签名不变）
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final Optional<McpConnection> connection;   // mock=true 时无此 bean → 接口报"暂不可用"

    public RagController(Optional<McpConnection> connection) {
        this.connection = connection;
    }

    // 上传文档（multipart 二进制文件）：绑定 userId；kbId 缺省 = 默认库
    // 二进制经 Base64 传 MCP（JSON 协议传不了裸二进制），rag-mcp 侧 is_base64 解码
    @PostMapping("/upload")
    public Result<String> upload(@RequestParam String userId,
                                 @RequestParam(required = false) String kbId,
                                 @RequestParam("file") MultipartFile file) throws IOException {
        String targetKb = kbId == null || kbId.isBlank() ? userId + "-default" : kbId;
        return call("rag_upload", Map.of(
                "user_id", userId,
                "kb_id", targetKb,
                "filename", file.getOriginalFilename(),
                "content", Base64.getEncoder().encodeToString(file.getBytes()),
                "is_base64", true));
    }

    // 列出我的知识库
    @GetMapping("/list")
    public Result<String> list(@RequestParam String userId) {
        return call("rag_list_kbs", Map.of("user_id", userId));
    }

    // 删除我的知识库（破坏性操作；用户体系后加二次确认）
    @DeleteMapping("/delete")
    public Result<String> delete(@RequestParam String userId, @RequestParam String kbId) {
        return call("rag_delete_kb", Map.of("user_id", userId, "kb_id", kbId));
    }

    // 检索我的知识库（kbId 缺省 = 默认库）
    @GetMapping("/search")
    public Result<String> search(@RequestParam String userId,
                                 @RequestParam(required = false) String kbId,
                                 @RequestParam String query,
                                 @RequestParam(defaultValue = "5") int topK) {
        String targetKb = kbId == null || kbId.isBlank() ? userId + "-default" : kbId;
        return call("rag_search", Map.of("user_id", userId, "kb_id", targetKb, "query", query, "top_k", topK));
    }

    // 统一收口：无连接（mock 模式）→ 503；调用失败 → 500
    private Result<String> call(String tool, Map<String, Object> args) {
        if (connection.isEmpty()) {
            return Result.fail(503, "RAG 暂不可用（未连接 rag-mcp，请将 linkagent.workflow.mock-enabled 置为 false）");
        }
        try {
            return Result.ok(connection.get().callTool(tool, args));
        } catch (Exception e) {
            return Result.fail(500, "RAG 调用失败: " + e.getMessage());
        }
    }
}