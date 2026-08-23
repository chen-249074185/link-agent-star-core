package com.star.agent.governance;

// Redis Key 规范工具类：所有 key 统一 linkagent:{模块}:{类型}:{标识}
// 好处：一眼看出归属、可按前缀批量管理、与其他应用共用 Redis 不冲突
public final class RedisKeys {

    private static final String PREFIX = "linkagent";
    private static final String MODULE_GOVERNANCE = "governance";
    private static final String TYPE_CONFIRMATION = "confirmation";
    private static final String TYPE_RATE = "rate";

    // 工具类惯例：final 类禁止继承 + 私有构造器禁止 new，只许静态调用
    private RedisKeys() {}

    // 高危确认态 key：确认谁对哪个技能放行过，如 linkagent:governance:confirmation:user1:liepin_apply
    // 入参 key 是治理层拼好的复合标识 "userId:skillName"（GovernanceKernel 传什么我们就存什么）
    public static String confirmation(String key) {
        return String.format("%s:%s:%s:%s", PREFIX, MODULE_GOVERNANCE, TYPE_CONFIRMATION, key);
    }

    // 单日限次 key：统计谁今天调过哪个技能几次，如 linkagent:governance:rate:user1:get_weather
    public static String rate(String key) {
        return String.format("%s:%s:%s:%s", PREFIX, MODULE_GOVERNANCE, TYPE_RATE, key);
    }
}