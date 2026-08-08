package com.star.agent.infra.web;

// code:状态码；msg：备注； data：获取的数据
// "用 ok() 时只需传 data，用 fail() 时需传 code+msg"
public record Result<T>(int code, String msg, T data) {

    public static <T> Result<T> ok(T data) {
        return new Result<>(0, "ok", data);
    }

    public static <T> Result<T> fail(int code, String msg) {
        return new Result<>(code, msg, null);
    }
}