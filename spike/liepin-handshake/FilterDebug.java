import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.star.liepin.FilterRules;
import com.star.liepin.JobFilter;
import com.star.liepin.LiepinCliRunner;

import java.util.List;

// 调试：真实岗位逐条打印四维判定，定位精筛 0 命中的原因
public class FilterDebug {
    public static void main(String[] args) throws Exception {
        String token = System.getenv("LIEPIN_USER_TOKEN");
        LiepinCliRunner runner = new LiepinCliRunner(LiepinCliRunner.resolveCliPath(), token);
        String out = runner.run("job", "search", "--job-name", "Java开发", "--address", "北京",
                "--output", "json");
        JsonNode data = new ObjectMapper().readTree(out).path("data");
        FilterRules rules = FilterRules.load();
        System.out.println("画像: 学历=" + rules.education() + " 经验关键词=" + rules.workYearKeywords()
                + " 薪资下限=" + rules.salaryFloorK() + "k 城市=" + rules.cities());

        int i = 0;
        for (JsonNode j : data.path("list")) {
            i++;
            JsonNode o = new ObjectMapper().createObjectNode();
            System.out.println("--- " + i + " jobId=" + j.path("jobId").asLong()
                    + " | edu=[" + j.path("education").asText() + "]"
                    + " | years=[" + j.path("workYears").asText() + "]"
                    + " | salary=[" + j.path("salary").asText() + "]"
                    + " | loc=[" + j.path("location").asText() + "]");
        }
        List<JobFilter.Match> matches = JobFilter.filter(data.path("list"), rules);
        System.out.println("命中数: " + matches.size());
    }
}