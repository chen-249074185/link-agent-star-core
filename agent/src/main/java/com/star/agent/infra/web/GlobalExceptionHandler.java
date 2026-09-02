package com.star.agent.infra.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

// 全局异常处理（W12 起启用，W14 计划书再补 traceId/完整日志栈）：
//   BizException → 按其携带的 code/msg 返回（业务规则话术，如"用户名已被占用"）
//   静态资源不存在 → 抛回 Spring 默认 404（不能让 /xxx.html 404 变成 500 JSON）
//   其他异常 → 500 统一话术 + 服务端完整日志（不把堆栈泄露给前端）
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public Result<Void> biz(BizException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public void noResource(NoResourceFoundException e) throws NoResourceFoundException {
        throw e;   // 交还 Spring 默认处理（404 页面/JSON），别被兜底 Exception 转成 500
    }

    // 上传超过 Tomcat multipart 上限（50MB）→ 友好话术，不是"服务器内部错误"（W13 上传 500 排查发现）
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> uploadTooLarge(MaxUploadSizeExceededException e) {
        return Result.fail(400, "文件过大（超过 50MB 限制）");
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> other(Exception e) {
        logger.error("未处理异常: {}", e.getMessage(), e);
        return Result.fail(500, "服务器内部错误");
    }
}