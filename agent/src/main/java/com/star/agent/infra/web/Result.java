package com.star.agent.infra.web;

import org.slf4j.MDC;

// code:状态码；msg：备注； data：获取的数据
// "用 ok() 时只需传 data，用 fail() 时需传 code+msg"
// traceId：本次请求的链路号（W14 11-10 加）——工厂方法实时从 MDC 取（TraceIdFilter 已写入），
//   前端/调用方拿到 traceId 即可在日志里定位整条请求链路；非 Web 链路（微信）MDC 也可能有值
public record Result<T>(int code, String msg, T data, String traceId) {

    public static <T> Result<T> ok(T data) {
        return new Result<>(0, "ok", data, currentTraceId());
    }

    public static <T> Result<T> fail(int code, String msg) {
        return new Result<>(code, msg, null, currentTraceId());
    }

    private static String currentTraceId() {
        return MDC.get("traceId");
    }
}