package com.star.agent.governance;

// 确认态存储合同：高危技能执行前要用户确认，存确认状态的接口
public interface ConfirmationStore {

    void save(String key, String value, long ttlSeconds);

    String get(String key);

    void delete(String key);
}