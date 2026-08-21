package com.star.agent.workflow;

import com.star.agent.skill.Skill;
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

    private final Map<String, Skill> skills = new HashMap<>();  // 技能索引：name → Skill（只收 MAIN/SCENE）

    private final WorkflowEngine engine;  // 通用执行器（传送带）

    // 构造器注入全部 Skill bean + 引擎，当场过滤 EXT
    public RentDecisionWorkflow(List<Skill> allSkills, WorkflowEngine engine) {
        for (Skill s : allSkills) {
            SkillType t = s.meta().skillType();
            if (t == SkillType.EXT) {
                continue;   // 拦截：EXT 技能不进 Workflow 技能库
            }
            String name = s.meta().name();
            if (skills.put(name, s) != null) {
                throw new IllegalStateException("技能重名: " + name);
            }
        }
        this.engine = engine;
    }

    // 按名字取技能（找不到抛异常，防止拼错）
    public Skill skill(String name) {
        Skill s = skills.get(name);
        if (s == null) {
            throw new IllegalArgumentException("Workflow 无此技能: " + name);
        }
        return s;
    }

    // 看 Workflow 收了多少技能（调试/测试用）
    public List<String> availableSkills() {
        return List.copyOf(skills.keySet());
    }

    // 跑整条流水线：输入 城市/预算 → 返回《租房决策报告》
    public String execute(String userId, String traceId, String city, int maxPrice) {
        WorkflowContext ctx = new WorkflowContext(userId, traceId);

        List<WorkflowNode> nodes = List.of(
            // 工序1：猎聘片区就业分析（参数=用户输入，固定）
            new SkillNode("jobs", skill("liepin_jobs"),
                    "{\"city\":\"" + city + "\"}"),
            // 工序2：RAG 房源筛选（参数=用户输入，固定）
            new SkillNode("houses", skill("rag_search"),
                    "{\"city\":\"" + city + "\",\"maxPrice\":" + maxPrice + "}"),
            // 工序3：通勤测算（mock 阶段固定示例坐标，W9 真实房源带坐标后改动态）
            new SkillNode("commutes", skill("commute_estimate"),
                    "{\"origin\":\"116.481028,39.989643\",\"destination\":\"116.465302,40.004717\"}"),
            // 工序4：风险检测（动态参数：从背包喂房源列表）
            new SkillNode("risks", skill("rent_risk"),
                    ctx2 -> "{\"houses\":" + ((SkillResult) ctx2.get("houses")).content() + "}"),
            // 工序5：加权打分（动态参数：从背包喂房源列表）
            new SkillNode("scores", skill("house_score"),
                    ctx2 -> "{\"houses\":" + ((SkillResult) ctx2.get("houses")).content() + "}"),
            // 工序6：汇总报告
            new ReportNode("report")
        );

        engine.execute(nodes, ctx);          // 传送带开跑
        return (String) ctx.get("report");   // 从背包取最终报告
    }
}
