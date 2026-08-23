import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

// spike: 验证 sqlite-vec (vec0.dll) 在 Windows + sqlite-jdbc 下能否加载并做 KNN 查询
public class Vec0Spike {

    public static void main(String[] args) throws Exception {
        // 关键：enable_load_extension=true 才允许 .load 外部扩展
        Properties props = new Properties();
        props.setProperty("enable_load_extension", "true");

        Class.forName("org.sqlite.JDBC");   // 显式注册驱动（新版不自动 SPI）

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:", props);
             Statement st = conn.createStatement()) {

            // 1. 加载 vec0 扩展（D37 的核心验证点：Windows 上能不能 load 成功）
            System.out.println("[1] 加载 vec0 扩展...");
            st.execute("SELECT load_extension('D:/星链智核/agent/spike/sqlite-vec/vec0.dll')");
            System.out.println("    扩展加载成功!");

            // 2. 建 vec0 虚拟表（8 维 float 向量）
            System.out.println("[2] 创建 vec0 虚拟表...");
            st.execute("CREATE VIRTUAL TABLE vec_examples USING vec0(sample_embedding float[8])");
            System.out.println("    建表成功!");

            // 3. 插入 4 条向量（JSON 格式）
            System.out.println("[3] 插入 4 条向量...");
            st.execute("INSERT INTO vec_examples(rowid, sample_embedding) VALUES " +
                    "(1, '[-0.200, 0.250, 0.341, -0.211, 0.645, 0.935, -0.316, -0.924]')," +
                    "(2, '[0.443, -0.501, 0.355, -0.771, 0.707, -0.708, -0.185, 0.362]')," +
                    "(3, '[0.716, -0.927, 0.134, 0.052, -0.669, 0.793, -0.634, -0.162]')," +
                    "(4, '[-0.710, 0.330, 0.656, 0.041, -0.990, 0.726, 0.385, -0.958]')");
            System.out.println("    插入成功!");

            // 4. KNN 查询：找与查询向量最近的 2 条
            System.out.println("[4] KNN 查询 (找最近 2 条)...");
            try (ResultSet rs = st.executeQuery(
                    "SELECT rowid, distance FROM vec_examples " +
                    "WHERE sample_embedding MATCH '[0.890, 0.544, 0.825, 0.961, 0.358, 0.0196, 0.521, 0.175]' " +
                    "ORDER BY distance LIMIT 2")) {
                while (rs.next()) {
                    System.out.println("    rowid=" + rs.getInt("rowid") + " distance=" + rs.getDouble("distance"));
                }
            }

            System.out.println("\n===== SPIKE 结论: sqlite-vec 在 Windows 加载 + KNN 查询全部成功! =====");
        }
    }
}