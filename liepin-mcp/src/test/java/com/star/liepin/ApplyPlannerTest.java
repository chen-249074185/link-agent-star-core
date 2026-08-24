package com.star.liepin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// ApplyPlanner 测试：dry-run 计划清单（只生成不投递）
class ApplyPlannerTest {

    @Test
    void buildsNumberedPlan() throws Exception {
        String jobs = "[{\"jobId\":85116009,\"jobType\":\"2\",\"jobName\":\"Java开发工程师\",\"company\":\"某科技公司\"},"
                + "{\"jobId\":85115197,\"jobType\":\"2\",\"jobName\":\"java高级开发\",\"company\":\"某网络公司\"}]";

        String plan = ApplyPlanner.buildDryRunPlan(jobs);

        assertTrue(plan.contains("投递计划"));
        assertTrue(plan.contains("未真实投递"));
        assertTrue(plan.contains("85116009"));
        assertTrue(plan.contains("85115197"));
        assertTrue(plan.contains("共 2 家"));
    }

    // 缺 jobType → 拒绝（真实投递需要它，现在就要校验）
    @Test
    void missingJobType_rejected() {
        String jobs = "[{\"jobId\":1,\"jobName\":\"没有类型\"}]";
        assertThrows(IllegalArgumentException.class, () -> ApplyPlanner.buildDryRunPlan(jobs));
    }

    // 空/非法输入 → 拒绝
    @Test
    void emptyInput_rejected() {
        assertThrows(IllegalArgumentException.class, () -> ApplyPlanner.buildDryRunPlan(""));
        assertThrows(IllegalArgumentException.class, () -> ApplyPlanner.buildDryRunPlan("not-json"));
    }
}