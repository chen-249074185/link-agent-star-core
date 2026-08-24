package com.star.liepin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

// 投递计划生成器（liepin_apply_dry_run）：把"选中的岗位"整理成拟投清单
// 只生成清单、不真实投递（安全边界：确认后才走真实 liepin_apply）
public class ApplyPlanner {

    private static final ObjectMapper mapper = new ObjectMapper();

    // jobsJson 形如 [{"jobId":85116009,"jobType":"2","jobName":"...","company":"..."}, ...]
    public static String buildDryRunPlan(String jobsJson) throws Exception {
        if (jobsJson == null || jobsJson.isBlank()) {
            throw new IllegalArgumentException("岗位列表不能为空");
        }
        JsonNode arr;
        try {
            arr = mapper.readTree(jobsJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("岗位列表格式错误（需 JSON 数组）: " + e.getMessage(), e);
        }
        if (!arr.isArray() || arr.isEmpty()) {
            throw new IllegalArgumentException("岗位列表需为 JSON 数组且不能为空");
        }
        StringBuilder sb = new StringBuilder("投递计划（dry-run，未真实投递）:\n");
        int i = 1;
        for (JsonNode j : arr) {
            if (!j.has("jobId") || !j.has("jobType")) {
                throw new IllegalArgumentException("岗位缺少 jobId/jobType（请从搜索结果取）");
            }
            sb.append(i++).append(". jobId=").append(j.get("jobId").asLong())
                    .append(" jobType=").append(j.get("jobType").asText())
                    .append(" | ").append(j.path("jobName").asText(""))
                    .append(" | ").append(j.path("company").asText(""))
                    .append("\n");
        }
        sb.append("共 ").append(i - 1).append(" 家。确认后发送真实投递指令。");
        return sb.toString();
    }
}