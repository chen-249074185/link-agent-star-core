package com.star.agent.infra.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

// 全局异常处理：
//   BizException → 按其携带的 code/msg 返回（业务规则话术，如"用户名已被占用"）
//   调用方问题（405 方法不支持/400 缺参数/上传超限）→ 友好话术，不打堆栈（不是服务端故障）
//   静态资源不存在 → 抛回 Spring 默认 404（不能让 /xxx.html 404 变成 500 JSON）
//   其他异常 → 500 统一话术 + 服务端完整日志（不把堆栈泄露给前端）
// 响应体统一 {code,msg,data,traceId}（Result 工厂实时从 MDC 取，TraceIdFilter 已写入，前端可凭 traceId 查日志）
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

    // 请求方法不支持（如 POST 接口用 GET 调）→ 405，不是服务端故障（11-10 实测发现被兜成 500）
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> methodNotSupported(HttpRequestMethodNotSupportedException e) {
        return Result.fail(405, "请求方式不支持（该接口支持 " + e.getSupportedHttpMethods() + "）");
    }

    // 缺少必填请求参数 → 400，调用方补参数即可
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> missingParam(MissingServletRequestParameterException e) {
        return Result.fail(400, "缺少参数: " + e.getParameterName());
    }

    // 请求体缺失/格式错（@RequestBody 解析失败，如 POST 空 body）→ 400，不是服务端故障
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public Result<Void> badBody(org.springframework.http.converter.HttpMessageNotReadableException e) {
        return Result.fail(400, "请求体缺失或格式错误");
    }

    // 上传超过 Tomcat multipart 上限（50MB）→ 友好话术，不是"服务器内部错误"
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> uploadTooLarge(MaxUploadSizeExceededException e) {
        return Result.fail(400, "文件过大（超过 50MB 限制）");
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> other(Exception e, jakarta.servlet.http.HttpServletRequest request) {
        String qs = request.getQueryString();
        logger.error("未处理异常: {} {}{}", request.getMethod(), request.getRequestURI(),
                qs != null ? "?" + qs : "", e);   // 完整日志栈（含 traceId，MDC 已写入）
        return Result.fail(500, "服务器内部错误");
    }
}