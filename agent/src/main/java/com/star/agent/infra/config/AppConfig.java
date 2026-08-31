package com.star.agent.infra.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@Component
public class AppConfig {

    private static final Logger logger = LoggerFactory.getLogger(AppConfig.class);

    // .env 文件兜底（Key=Value 一行一个，# 开头是注释，空行跳过）
    // 场景：IDEA 启动早于环境变量配置时不刷新系统变量，从 .env 读也能拿到 key
    private static final Map<String, String> DOTENV = loadDotEnv();

    private final String dashScopeApiKey = firstOf("DASHSCOPE_API_KEY");

    private final String adminToken = firstOf("ADMIN_TOKEN_WEB");

    private final String deepseekApiKey = firstOf("DEEPSEEK_API_KEY");

    private final String seniverseApiKey = firstOf("SENIVERSE_API_KEY");

    private final String amapApiKey = firstOf("AMAP_KEY");

    private final String liepinUserToken = firstOf("LIEPIN_USER_TOKEN");   // 猎聘授权 Token（.env 已 gitignore）

    private final String mailUser = firstOf("MAIL_USER");            // 发信 QQ 邮箱（需开启 SMTP 服务）

    private final String mailAuthCode = firstOf("MAIL_AUTH_CODE");   // QQ 邮箱授权码（非登录密码）

    private final String jwtSecret = firstOf("JWT_SECRET");          // JWT 签名密钥（HS256 ≥32 字节）

    // 构造自检：各 key 是否就位 + 脱敏指纹（后4位，对比 .env/环境变量是否同 key；纯 main 扫码链路也走这个构造器）
    public AppConfig() {
        logger.info("配置自检: DEEPSEEK={} SENIVERSE={} AMAP={} AMAP指纹={} (长度, 0=缺失, 来源={})",
                deepseekApiKey.length(), seniverseApiKey.length(), amapApiKey.length(), fingerprint(amapApiKey),
                DOTENV.isEmpty() ? "仅环境变量" : "环境变量+.env文件");
    }

    // 指纹：非空则取后4位，用于对比两个来源的 key 是否同一个（不泄露明文）
    private static String fingerprint(String key) {
        return (key == null || key.length() < 4) ? "无" : key.substring(key.length() - 4);
    }

    // 读 .env 文件（工作目录下，IDEA 跑 main 时 = 项目根 agent/）
    private static Map<String, String> loadDotEnv() {
        Map<String, String> map = new HashMap<>();
        try {
            Path p = Path.of(".env");
            if (Files.exists(p)) {
                for (String line : Files.readAllLines(p)) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int i = line.indexOf('=');
                    if (i > 0) {
                        map.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return map;
    }

    // 读值顺序：系统环境变量优先，.env 文件兜底
    private static String firstOf(String key) {
        String env = System.getenv(key);
        return (env != null && !env.isBlank()) ? env : DOTENV.getOrDefault(key, "");
    }

    public String dashScopeApiKey(){
        return dashScopeApiKey;
    }

    public String adminToken(){
        return adminToken;
    }

    public String deepseekApiKey(){
        return deepseekApiKey;
    }

    public String seniverseApiKey(){return seniverseApiKey; }

    public String amapApiKey(){ return amapApiKey; }

    public String liepinUserToken(){ return liepinUserToken; }

    public String mailUser(){ return mailUser; }

    public String mailAuthCode(){ return mailAuthCode; }

    public String jwtSecret(){ return jwtSecret; }

}