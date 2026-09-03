package com.star.agent.workflow;

import com.star.agent.skill.Skill;
import com.star.agent.skill.SkillRegistry;
import com.star.agent.skill.SkillResult;
import com.star.agent.skill.SkillType;
import com.star.agent.workflow.base.WorkflowEngine;
import com.star.agent.workflow.context.WorkflowContext;
import com.star.agent.workflow.node.ReportNode;
import com.star.agent.workflow.node.SkillNode;
import com.star.agent.workflow.node.WorkflowNode;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 租房决策主线编排器：把工序技能串成流水线（仅 MAIN/SCENE 可入流，EXT 被拦截）
@Component
public class RentDecisionWorkflow {

    // MCP 双轨技能名映射：逻辑名 → 真实 MCP 技能名（mock=false 时兜底查找）
    // mock=true：rag_search/liepin_jobs（Mock 占位）直接命中；mock=false：落到 mcp_ 前缀真实技能
    private static final Map<String, String> MCP_ALIASES = Map.of(
            "rag_search", "mcp_rag_search",
            "liepin_jobs", "mcp_liepin_search_jobs"
    );

    // 共享房源库（11-03 方案1）：房源=通用公开数据（爬链家），全用户只读可检索。
    // 真实模式 rag_search 固定检索共享库，不再依赖用户自己传房源；岗位仍走用户私有库
    public static final String SHARED_HOUSES_KB = "kb-shared-houses";

    private final SkillRegistry skillRegistry;   // 技能花名册（含本地 @Component + 程序化注册的 MCP 技能）
    private final WorkflowEngine engine;         // 通用执行器（传送带）

    // 注入 SkillRegistry 而非 List<Skill>：MCP 技能是 @PostConstruct 程序化注册进花名册的，
    // 构造时 List 注入拿不到 → 执行时懒解析（skill() 每次查花名册，天然规避注册时序）
    public RentDecisionWorkflow(SkillRegistry skillRegistry, WorkflowEngine engine) {
        this.skillRegistry = skillRegistry;
        this.engine = engine;
    }

    // 按逻辑名取技能（执行时查花名册）：先精确名，再 MCP 别名兜底；EXT 技能绝不进流程
    public Skill skill(String name) {
        Skill s = skillRegistry.getSkill(name);
        if (s == null) {
            s = skillRegistry.getSkill(MCP_ALIASES.get(name));   // 逻辑名 → mcp_ 前缀真实技能
        }
        if (s == null || s.meta().skillType() == SkillType.EXT) {
            throw new IllegalArgumentException("Workflow 无此技能: " + name);
        }
        return s;
    }

    // 看 Workflow 可用的技能（过滤 EXT，调试/测试用）
    public List<String> availableSkills() {
        return skillRegistry.allSkills().stream()
                .filter(s -> s.meta().skillType() != SkillType.EXT)
                .map(s -> s.meta().name())
                .sorted()
                .toList();
    }

    // 跑整条流水线：输入 城市/预算 → 返回《租房决策报告》
    public String execute(String userId, String traceId, String city, int maxPrice) {
        WorkflowContext ctx = new WorkflowContext(userId, traceId);

        List<WorkflowNode> nodes = List.of(
            // 工序1：猎聘片区就业分析（参数双兼容：Mock 读 city；真实 MCP 读 keyword/address）
            new SkillNode("jobs", skill("liepin_jobs"),
                    "{\"city\":\"" + city + "\",\"keyword\":\"Java开发\",\"address\":\"" + city + "\"}"),
            // 工序2：RAG 房源检索（参数双兼容：Mock 读 city/maxPrice；真实 MCP 读 query 做语义检索）
            // kb_id 固定共享房源库（只读全局库，房源=公开数据；McpSkill 白名单放行此值）
            new SkillNode("houses", skill("rag_search"),
                    "{\"city\":\"" + city + "\",\"maxPrice\":" + maxPrice
                            + ",\"query\":\"" + city + " 预算" + maxPrice + "元以内 房源 租房\""
                            + ",\"kb_id\":\"" + SHARED_HOUSES_KB + "\"}"),
            // 工序3：通勤测算（mock 阶段固定示例坐标，真实房源带坐标后改动态）
            new SkillNode("commutes", skill("commute_estimate"),
                    "{\"origin\":\"116.481028,39.989643\",\"destination\":\"116.465302,40.004717\"}"),
            // 工序4：风险检测（动态参数：真实 RAG 自由文本 → HouseTextParser 结构化房源 JSON）
            new SkillNode("risks", skill("rent_risk"),
                    ctx2 -> "{\"houses\":" + HouseTextParser.toJson(((SkillResult) ctx2.get("houses")).content()) + "}"),
            // 工序5：加权打分（同上，双模式兼容：Mock JSON 透传 / 真实文本解析）
            new SkillNode("scores", skill("house_score"),
                    ctx2 -> "{\"houses\":" + HouseTextParser.toJson(((SkillResult) ctx2.get("houses")).content()) + "}"),
            // 工序6：汇总报告
            new ReportNode("report")
        );

        engine.execute(nodes, ctx);          // 传送带开跑
        return (String) ctx.get("report");   // 从背包取最终报告
    }
}
