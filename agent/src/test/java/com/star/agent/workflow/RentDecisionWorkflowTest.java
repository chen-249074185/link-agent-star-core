package com.star.agent.workflow;

import com.star.agent.skill.SkillResult;
import com.star.agent.skill.impl.CommuteSkill;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

// 租房决策主线编排测试：EXT 拦截 + 全链路跑通出报告
@SpringBootTest
class RentDecisionWorkflowTest {

    @Autowired
    private RentDecisionWorkflow workflow;

    @SpyBean
    private CommuteSkill commuteSkill;  // 包一层真技能、只替换 execute：避免测试真打高德 API

    @Test
    void extSkill_get_weather_isBlocked() {
        List<String> skills = workflow.availableSkills();
        assertFalse(skills.contains("get_weather"));  // EXT 被拦截
        assertTrue(skills.contains("rag_search"));     // MAIN 在
        assertTrue(skills.contains("liepin_jobs"));    // MAIN 在
        assertTrue(skills.contains("uu_quote"));       // SCENE 在
    }

    @Test
    void execute_runsFullPipelineAndReturnsReport() {
        doReturn(new SkillResult(true, "通勤40分钟")).when(commuteSkill).execute(any());

        String report = workflow.execute("u1", "t1", "北京", 6000);

        assertTrue(report.contains("海淀学院路两居"));  // rag mock 房源出现
        assertTrue(report.contains("打分排行"));        // house_score 输出出现
    }

    @Test
    void execute_reportContainsAllFiveSections() {
        doReturn(new SkillResult(true, "通勤40分钟")).when(commuteSkill).execute(any());

        String report = workflow.execute("u1", "t1", "上海", 5000);

        // 报告聚合模板：五段全齐，顺序固定
        assertTrue(report.contains("【一、片区就业分析】"));
        assertTrue(report.contains("【二、候选房源】"));
        assertTrue(report.contains("【三、通勤测算】"));
        assertTrue(report.contains("【四、风险检测】"));
        assertTrue(report.contains("【五、加权打分排行】"));
        assertTrue(report.indexOf("【一、片区就业分析】") < report.indexOf("【五、加权打分排行】"));
    }
}
