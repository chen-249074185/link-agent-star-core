package com.star.agent.user;

import com.star.agent.infra.web.Result;

// 用户认证接口（登录/注册页前端调用，契约 10-25 冻结）：
//   POST /api/user/send-code  {"email"}                          = 发验证码（60s 限流）
//   POST /api/user/register   {"type":"username","username","password"} | {"type":"email","email","code"}
//   POST /api/user/login      {"type":"username","username","password"} | {"type":"email","email","code"}
//     成功 data = {token, userId}（邮箱路径未注册=自动注册，登录即注册）
//  JWT 拦截器就绪后 userId 从 token 取，本接口签名不变
public record AuthRequest(String type, String username, String password, String email, String code) {
}