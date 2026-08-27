package com.star.agent.infra.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// 全局异常处理（W12 起启用，W14 计划书再补 traceId/完整日志栈）：
//   BizException → 按其携带的 code/msg 返回（业务规则话术，如"用户名已被占用"）
//   其他异常 → 500 统一话术 + 服务端完整日志（不把堆栈泄露给前端）
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public Result<Void> biz(BizException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> other(Exception e) {
        logger.error("未处理异常: {}", e.getMessage(), e);
        return Result.fail(500, "服务器内部错误");
    }
}