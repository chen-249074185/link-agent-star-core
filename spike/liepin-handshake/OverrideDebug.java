import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.star.liepin.FilterRules;
import com.star.liepin.JobFilter;
import com.star.liepin.LiepinCliRunner;

import java.util.List;
import java.util.Map;

// 调试：对话式规则覆盖（用户说"看硕士岗，经验放宽到3-5年，其他不变"）
public class OverrideDebug {
    public static void main(String[] args) throws Exception {
        String token = System.getenv("LIEPIN_USER_TOKEN");
        LiepinCliRunner runner = new LiepinCliRunner(LiepinCliRunner.resolveCliPath(), token);
        String out = runner.run("job", "search", "--job-name", "Java开发", "--address", "北京",
                "--output", "json");
        JsonNode data = new ObjectMapper().readTree(out).path("data");

        FilterRules base = FilterRules.load();
        FilterRules overridden = base.withOverride(Map.of(
                "education", "硕士",
                "workYearKeywords", List.of("经验不限", "应届", "实习", "以下", "3-5年")));

        System.out.println("默认画像: 学历=" + base.education() + " 薪资≥" + base.salaryFloorK() + "k 城市=" + base.cities());
        System.out.println("覆盖后画像: 学历=" + overridden.education() + " 经验=" + overridden.workYearKeywords()
                + " 薪资≥" + overridden.salaryFloorK() + "k 城市=" + overridden.cities() + " (薪资/城市未覆盖=仍用默认)");

        List<JobFilter.Match> matches = JobFilter.filter(data.path("list"), overridden);
        System.out.println("命中数: " + matches.size());
        for (JobFilter.Match m : matches) {
            System.out.println("  jobId=" + m.job().path("jobId").asLong()
                    + " | " + m.job().path("jobName").asText()
                    + " | " + m.job().path("salary").asText()
                    + " | " + m.job().path("education").asText()
                    + " | " + m.job().path("workYears").asText()
                    + " | " + m.reason());
        }
    }
}