package com.star.agent.governance;

// 限次接口：统计"某人在某时间段内调用某技能几次"
public interface RateLimiter {

    int increment(String key);   // 记录一次调用，返回累计次数

    int count(String key);       // 只查当前次数，不增加
}
