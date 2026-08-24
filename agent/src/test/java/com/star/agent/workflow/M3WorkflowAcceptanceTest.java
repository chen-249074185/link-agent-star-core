package com.star.agent.workflow;

import com.star.agent.mcp.McpConnection;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

// M3 验收：mock=false 真实模式 Workflow 全链路（真实 MCP 三 server + 真实 API）
// @Disabled：依赖真实子进程/网络/额度，手动运行（临时去掉注解）：mvn test -Dtest=M3WorkflowAcceptanceTest
@SpringBootTest(properties = "linkagent.workflow.mock-enabled=false")
@Disabled("手动验收用（真实 MCP + 真实 API），不进常规套件")
class M3WorkflowAcceptanceTest {

    @Autowired
    private RentDecisionWorkflow workflow;

    @Autowired
    private McpConnection ragConnection;   // @Primary：rag-mcp 连接

    @Test
    void fullPipelineWithRealMcp() throws Exception {
        // 准备：给 u1 的默认库上传房源知识（走真实 rag_upload 管道）
        String houses = "北京海淀学院路两居室月租6500元，通勤中关村35分钟。\n\n"
                + "北京朝阳望京单间月租3800元，通勤中关村50分钟。\n\n"
                + "北京昌平回龙观一居月租4200元，通勤中关村60分钟。";
        var up = ragConnection.callTool("rag_upload", Map.of(
                "user_id", "u1", "kb_id", "u1-default",
                "filename", "房源知识.txt",
                "content", Base64.getEncoder().encodeToString(houses.getBytes()),
                "is_base64", true));
        System.out.println("[准备] 上传房源知识: " + up);

        // 跑 Workflow：真实 MCP 技能（mcp_liepin_search_jobs / mcp_rag_search）+ 真实高德通勤
        String report = workflow.execute("u1", "m3accept", "北京", 6000);

        System.out.println("================== M3 真实模式报告 ==================");
        System.out.println(report);
        System.out.println("======================================================");

        assertTrue(report.contains("一、片区就业分析"), "缺就业分析段");
        assertTrue(report.contains("二、候选房源"), "缺房源段");
        assertTrue(report.contains("三、通勤测算"), "缺通勤段");
        assertTrue(report.contains("四、风险检测"), "缺风险段");
        assertTrue(report.contains("五、加权打分排行"), "缺打分段");
    }
}