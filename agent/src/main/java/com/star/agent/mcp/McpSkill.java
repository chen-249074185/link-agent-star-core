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

    // 身份/内部字段白名单外的黑名单：只允许代码注入，绝不被 LLM 参数覆盖（防伪造他人身份访问，R10）
    // mergeLlmArgs 遇到这些 key 直接跳过；execute() 里已用 ctx.userId() 注入正确值
    // 例外：kb_id 若显式指定"共享房源库"则放行——它是全局只读库（人人可查、写操作被 rag-mcp 拒），
    // 无隐私风险，Workflow 房源检索靠它指向共享库（11-03 方案1）
    private static final List<String> PROTECTED_ARGS = List.of(
            "user_id", "kb_id", "is_base64", "user_token");

    private static final String SHARED_HOUSES_KB = "kb-shared-houses";   // 与 rag-mcp SQLiteVecStore 常量一致

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
            // required 必须同步清理：properties 已删的字段若仍留在 required，
            // schema 畸形（required 引用不存在的属性）→ LLM 拒绝调用该工具（实测"杭州租房"不调工具直接空泛回复）
            if (params.get("required") instanceof List<?> required) {
                required.removeIf(k -> k.equals("user_id") || k.equals("kb_id")
                        || k.equals("is_base64") || k.equals("user_token"));
            }
        }

        // 群聊 GROUP 不在白名单 → 群聊场景自动禁用（风控）；
        // 猎聘求职域工具额外加 OFFICE（"简历/求职"关键词会命中 OFFICE 场景，不加就"当前场景不可用"）
        List<String> scenes = "liepin-mcp".equals(connection.serverName())
                ? List.of("CHAT", "DATA", "OFFICE")
                : List.of("CHAT", "DATA");
        String desc = tool.description();
        if ("rag_search".equals(tool.name())) {
            // 模型默认会查用户私有库（可能为空）→ 提示共享房源库的存在（kb_id 白名单放行，见 mergeLlmArgs）
            // 并引导串联通勤：出发地必须先问用户（IP 定位不可靠，方案 A 用户拍板）
            desc += "（默认查用户私有知识库；检索公开房源请传 kb_id=kb-shared-houses，内含全国 34 城链家房源。"
                    + "检索到候选房源后，先询问用户出发位置，再用 commute_estimate 测算从出发地到房源的通勤并给出查看路线链接）";
        }
        // 高危判定：破坏性/花钱操作必须经治理内核"确认放行"闭环——
        // delete（删库/删文件）、apply（猎聘投递）、下单类（瑞幸 createOrder 真实点单）
        // 其余（查询/预览/算价）只读安全，直接放行
        String n = tool.name().toLowerCase();
        boolean highRisk = n.contains("delete") || n.contains("apply")
                || n.contains("createorder") || n.contains("create_order") || n.contains("create-order");
        return new SkillMeta(
                PREFIX + tool.name(),
                desc + "（由 MCP 子进程提供，回答请标注来源）",
                params,
                highRisk ? RiskLevel.HIGH_RISK : RiskLevel.LOW,   // 删库/投递/下单=真实花钱或破坏性操作，治理内核确认放行
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
    // 安全闸：PROTECTED_ARGS（user_id/kb_id/is_base64/user_token）跳过——身份/内部标志只能由 execute() 注入，
    // 模型即便在参数里编造这些 key 也覆盖不了（防 LLM 伪造他人身份访问私有库，R10）
    private void mergeLlmArgs(Map<String, Object> args, String llmArgsJson) throws Exception {
        if (llmArgsJson == null || llmArgsJson.isBlank()) {
            return;
        }
        JsonNode node = mapper.readTree(llmArgsJson);
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (PROTECTED_ARGS.contains(e.getKey())) {
                // 唯一例外：kb_id=共享房源库（只读全局库，人人可查，Workflow 靠它检索公开房源）
                if ("kb_id".equals(e.getKey()) && SHARED_HOUSES_KB.equals(e.getValue().asText())) {
                    args.put("kb_id", SHARED_HOUSES_KB);
                }
                continue;   // 其余身份/内部字段不采信 LLM 值，保持 execute() 注入的 ctx.userId() 身份
            }
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