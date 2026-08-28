package com.star.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.star.agent.skill.JsonSchemaBuilder;
import com.star.agent.skill.RiskLevel;
import com.star.agent.skill.Skill;
import com.star.agent.skill.SkillContext;
import com.star.agent.skill.SkillMeta;
import com.star.agent.skill.SkillResult;
import com.star.agent.skill.SkillType;
import com.star.agent.user.UserConfigRepository;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// MCP 工具转 Skill 的适配器（双轨架构"MCP 轨"）：把 rag-mcp 的 MCP 工具包装成标准 Skill
// 入治理内核统一安检 + 进 Workflow 统一编排，调用方感知不到它来自子进程
public class McpSkill implements Skill {

    private static final String PREFIX = "mcp_";              // 工具名前缀：rag_search → mcp_rag_search

    private final McpSchema.Tool tool;                        // 原始 MCP 工具说明（名字/描述/Schema）
    private final McpConnection connection;                   // 调子进程的唯一通道
    private final SkillMeta meta;                             // 预构建（构造时算好，避免每次 execute 重算）
    private final UserConfigRepository userConfigRepository;  // 用户配置（liepin 域取每用户自己的 Token）；null=手动链路
    private final ObjectMapper mapper = new ObjectMapper();

    public McpSkill(McpSchema.Tool tool, McpConnection connection) {
        this(tool, connection, null);
    }

    public McpSkill(McpSchema.Tool tool, McpConnection connection, UserConfigRepository userConfigRepository) {
        this.tool = tool;
        this.connection = connection;
        this.userConfigRepository = userConfigRepository;
        this.meta = buildMeta();
    }

    @Override
    public SkillMeta meta() {
        return meta;
    }

    private SkillMeta buildMeta() {
        // 入参过滤：user_id/kb_id/is_base64 由上下文/上层注入，不暴露给 LLM（防 LLM 乱填他人身份）
        // 注意：字段名在 JSON Schema 的 properties 嵌套层，不是顶层 keySet
        Map<String, Object> params = toParameters(tool.inputSchema());
        Object propsObj = params.get("properties");
        if (propsObj instanceof Map<?, ?> props) {
            // user_id/kb_id/is_base64/user_token 均由上下文/上层注入，不暴露给 LLM（防乱填他人身份）
            props.keySet().removeIf(k -> k.equals("user_id") || k.equals("kb_id")
                    || k.equals("is_base64") || k.equals("user_token"));
        }

        // 群聊 GROUP 不在白名单 → 群聊场景自动禁用（风控）；
        // 猎聘求职域工具额外加 OFFICE（"简历/求职"关键词会命中 OFFICE 场景，不加就"当前场景不可用"）
        List<String> scenes = "liepin-mcp".equals(connection.serverName())
                ? List.of("CHAT", "DATA", "OFFICE")
                : List.of("CHAT", "DATA");
        return new SkillMeta(
                PREFIX + tool.name(),
                tool.description() + "（由 MCP 子进程提供，回答请标注来源）",
                params,
                tool.name().contains("delete") || tool.name().contains("apply")
                        ? RiskLevel.HIGH_RISK : RiskLevel.LOW,   // 删库/投递=破坏性操作，治理内核确认放行
                scenes,
                "MCP:" + connection.serverName(),   // 来源标注：MCP:rag-mcp / MCP:crawl-mcp
                SkillType.MAIN
        );
    }

    // 执行：LLM 参数 + 工单身份（userId）→ 拼 MCP 参数 → 调子进程 → 回填结果
    @Override
    public SkillResult execute(SkillContext ctx) {
        try {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("user_id", ctx.userId());                // 身份从工单来，不由 LLM 填
            args.put("kb_id", ctx.userId() + "-default");     // 默认库（10-02 接口支持指定库）
            // 猎聘域：注入该用户自己的 Token（user_config 按 userId 隔离）。
            // 未配置 → 明确拒绝并引导配置，绝不回退用别人的 Token（用户私有数据，宁缺勿串）。
            // 例外：liepin_doctor 是接入自检工具（无用户维度），走全局环境变量 Token，不查用户配置
            if ("liepin-mcp".equals(connection.serverName()) && !"liepin_doctor".equals(tool.name())) {
                String token = userConfigRepository == null ? null
                        : userConfigRepository.findLiepinToken(ctx.userId());
                if (token == null || token.isBlank()) {
                    return new SkillResult(false,
                            "你还没配置猎聘 Token。请直接把你的猎聘 Token 发给我（获取：猎聘官网 https://www.liepin.com/mcp/auth 登录生成），我会自动为你保存，之后查岗位/简历都走你自己的猎聘账号。");
                }
                args.put("user_token", token);
            }
            mergeLlmArgs(args, ctx.args());

            String content = connection.callTool(tool.name(), args);
            return new SkillResult(true, content);
        } catch (Exception e) {
            // R9 降级：子进程失联/调用失败 → 返回话术不抛异常，主流程不崩
            return new SkillResult(false, "RAG 检索暂不可用: " + e.getMessage());
        }
    }

    // 把 LLM 传来的 JSON 参数并入 MCP 参数（数值/布尔转成真实类型，别全塞字符串）
    private void mergeLlmArgs(Map<String, Object> args, String llmArgsJson) throws Exception {
        if (llmArgsJson == null || llmArgsJson.isBlank()) {
            return;
        }
        JsonNode node = mapper.readTree(llmArgsJson);
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            args.put(e.getKey(), toPlainValue(e.getValue()));
        }
    }

    private Object toPlainValue(JsonNode n) {
        if (n.isIntegralNumber()) return n.asInt();
        if (n.isFloatingPointNumber()) return n.asDouble();
        if (n.isBoolean()) return n.asBoolean();
        return n.asText();
    }

    // MCP JsonSchema（properties 是 Map<String,Object>，值含 type/description）→ 项目标准 parameters Map
    private Map<String, Object> toParameters(McpSchema.JsonSchema schema) {
        JsonSchemaBuilder b = JsonSchemaBuilder.object();
        if (schema != null && schema.properties() != null) {
            for (Map.Entry<String, Object> e : schema.properties().entrySet()) {
                String name = e.getKey();
                Map<?, ?> prop = (Map<?, ?>) e.getValue();
                String type = String.valueOf(prop.get("type"));
                Object descObj = prop.get("description");
                String desc = descObj == null ? "" : String.valueOf(descObj);
                boolean required = schema.required() != null && schema.required().contains(name);
                if ("integer".equals(type)) {
                    b.addInteger(name, desc, required);
                } else {
                    b.addString(name, desc, required);   // 其余类型按 string 暴露（够 LLM 用）
                }
            }
        }
        return b.build();
    }
}