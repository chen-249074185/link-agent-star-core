package com.star.liepin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// JobFilter 测试：学历/经验/薪资/城市四维规则（用真实 API 返回的岗位字段形状）
class JobFilterTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    private static final FilterRules RULES =
            new FilterRules("本科", List.of("经验不限", "应届", "实习", "以下"), 10, List.of("北京", "上海", "杭州", "南京"));

    // 全过：本科 + 经验不限 + 10-20k + 北京 → 命中
    @Test
    void matchesAllFourDimensions() throws Exception {
        JsonNode jobs = array(
                "{\"jobId\":1,\"jobType\":\"2\",\"jobName\":\"Java开发\",\"company\":\"A\","
                        + "\"salary\":\"10-20k\",\"education\":\"本科\",\"workYears\":\"经验不限\",\"location\":\"北京-西二旗\"}",
                "{\"jobId\":2,\"jobType\":\"2\",\"jobName\":\"算法工程师\",\"company\":\"B\","
                        + "\"salary\":\"50-80k·16薪\",\"education\":\"硕士\",\"workYears\":\"3-5年\",\"location\":\"北京-望京\"}");

        List<JobFilter.Match> matches = JobFilter.filter(jobs, RULES);

        assertEquals(1, matches.size());
        assertEquals(1, matches.get(0).job().path("jobId").asLong());
        assertTrue(matches.get(0).reason().contains("四维全过"));
    }

    // 硕士起 → 学历不过；3-5年 → 经验不过；期望城市外 → 城市不过；薪资低于下限 → 不过
    @Test
    void rejectsEachDimension() throws Exception {
        JsonNode jobs = array(
                "{\"jobId\":11,\"jobType\":\"2\",\"jobName\":\"硕士岗\",\"company\":\"A\","
                        + "\"salary\":\"20-30k\",\"education\":\"硕士\",\"workYears\":\"经验不限\",\"location\":\"北京\"}",
                "{\"jobId\":12,\"jobType\":\"2\",\"jobName\":\"资深岗\",\"company\":\"B\","
                        + "\"salary\":\"20-30k\",\"education\":\"本科\",\"workYears\":\"5-10年\",\"location\":\"北京\"}",
                "{\"jobId\":13,\"jobType\":\"2\",\"jobName\":\"低薪岗\",\"company\":\"C\","
                        + "\"salary\":\"4-7k\",\"education\":\"本科\",\"workYears\":\"经验不限\",\"location\":\"北京\"}",
                "{\"jobId\":14,\"jobType\":\"2\",\"jobName\":\"外地岗\",\"company\":\"D\","
                        + "\"salary\":\"15-25k\",\"education\":\"本科\",\"workYears\":\"经验不限\",\"location\":\"广州-天河\"}");

        assertEquals(0, JobFilter.filter(jobs, RULES).size());
    }

    // 薪资"面议"/学历不限/经验 1年以下 → 都放行（应届友好形态）
    @Test
    void negotiableAndFlexibleFieldsPass() throws Exception {
        JsonNode jobs = array(
                "{\"jobId\":21,\"jobType\":\"2\",\"jobName\":\"面议岗\",\"company\":\"A\","
                        + "\"salary\":\"面议\",\"education\":\"学历不限\",\"workYears\":\"1年以下\",\"location\":\"杭州-滨江\"}");

        List<JobFilter.Match> matches = JobFilter.filter(jobs, RULES);

        assertEquals(1, matches.size());
        assertEquals(21, matches.get(0).job().path("jobId").asLong());
    }

    // 规则从 YAML 加载：education=本科 / 薪资下限 10 / 城市四城
    @Test
    void loadsRulesFromYaml() {
        FilterRules rules = FilterRules.load();
        assertEquals("本科", rules.education());
        assertEquals(10, rules.salaryFloorK());
        assertTrue(rules.cities().contains("北京"));
    }

    // 对话式覆盖：只覆盖学历 → 其他维度保持原值（用户说"看硕士岗，其他不变"）
    @Test
    void withOverride_changesOnlyGivenDimension() {
        FilterRules base = FilterRules.load();
        FilterRules overridden = base.withOverride(Map.of("education", "硕士"));

        assertEquals("硕士", overridden.education());
        assertEquals(base.salaryFloorK(), overridden.salaryFloorK());   // 薪资没提 → 不变
        assertEquals(base.workYearKeywords(), overridden.workYearKeywords());
        assertEquals(base.cities(), overridden.cities());
    }

    // 覆盖多个维度 + 空覆盖不动
    @Test
    void withOverride_multiAndEmpty() {
        FilterRules base = FilterRules.load();
        FilterRules multi = base.withOverride(Map.of("salaryFloorK", 8, "cities", List.of("北京", "成都")));
        assertEquals(8, multi.salaryFloorK());
        assertTrue(multi.cities().contains("成都"));

        assertEquals(base, base.withOverride(Map.of()));   // 空覆盖 → 原样
        assertEquals(base, base.withOverride(null));
    }

    private static JsonNode array(String... jobs) throws Exception {
        return mapper.readTree("[" + String.join(",", jobs) + "]");
    }
}